using System.Runtime.InteropServices;

namespace BadgeCheckIn;

/// <summary>
/// Minimal PC/SC wrapper over winscard.dll. One background thread watches the
/// first connected reader and raises CardTapped with the card UID whenever a
/// card arrives. UID is read with the standard CCID pseudo-APDU
/// FF CA 00 00 00 (Get Data: UID), which OMNIKEY 5022 / ACR122U both honor.
/// </summary>
public sealed class PcscReader : IDisposable
{
    public event Action<byte[]>? CardTapped;      // raw UID bytes
    public event Action<string>? StatusChanged;   // human-readable reader state

    private IntPtr _context;
    private Thread? _watch;
    private volatile bool _stop;

    public void Start()
    {
        _watch = new Thread(WatchLoop) { IsBackground = true, Name = "pcsc-watch" };
        _watch.Start();
    }

    /// <summary>Establishes the PC/SC context on demand, reporting instead of
    /// throwing. On modern Windows the Smart Card service is trigger-started
    /// only when a reader is attached, so a kiosk launched with no reader
    /// plugged in has no service and no context — a state to wait out, not a
    /// crash. Plugging the reader in starts the service and the next retry
    /// succeeds.</summary>
    private bool EnsureContext()
    {
        if (_context != IntPtr.Zero) return true;
        if (SCardEstablishContext(ScopeSystem, IntPtr.Zero, IntPtr.Zero, out _context) != 0)
        {
            _context = IntPtr.Zero;
            StatusChanged?.Invoke("No reader detected — plug in the USB reader.");
            return false;
        }
        return true;
    }

    private void WatchLoop()
    {
        string? reader = null;
        bool cardWasPresent = false;
        var state = default(ScardReaderState);

        while (!_stop)
        {
            if (!EnsureContext())
            {
                Thread.Sleep(1500);
                continue;
            }

            if (reader is null)
            {
                reader = FirstReaderOrNull(out bool contextDead);
                if (contextDead)
                {
                    // Service died while we were polling for a reader: the
                    // context is dead too. Drop it; EnsureContext retries.
                    SCardReleaseContext(_context);
                    _context = IntPtr.Zero;
                    continue;
                }
                if (reader is null)
                {
                    StatusChanged?.Invoke("No reader detected — plug in the USB reader.");
                    Thread.Sleep(1500);
                    continue;
                }
                StatusChanged?.Invoke($"Reader ready: {reader}");
                // Atr must be pre-allocated: SCARD_READERSTATE carries an inline
                // 36-byte ATR buffer and marshalling a null ByValArray throws.
                state = new ScardReaderState
                {
                    Reader = reader, CurrentState = StateUnaware, Atr = new byte[36]
                };
            }

            int rc = SCardGetStatusChange(_context, 1000, ref state, 1);
            if (rc == ErrTimeout) continue;
            if (IsContextDead(rc))
            {
                // The service died (last reader unplugged): the context is dead
                // with it. Drop it and let EnsureContext re-establish.
                SCardReleaseContext(_context);
                _context = IntPtr.Zero;
                reader = null; cardWasPresent = false;
                continue;
            }
            if (rc != 0) { reader = null; cardWasPresent = false; continue; } // reader unplugged etc.

            bool present = (state.EventState & StatePresent) != 0;
            if (present && !cardWasPresent)
            {
                var uid = TryReadUid(reader);
                if (uid is { Length: > 0 }) CardTapped?.Invoke(uid);
                else StatusChanged?.Invoke("Card seen but UID read failed — try again.");
            }
            cardWasPresent = present;
            // Per the PC/SC contract, feed the reported state back so the next
            // call blocks until an actual change instead of returning instantly.
            state.CurrentState = state.EventState & ~StateChanged;
        }
    }

    private string? FirstReaderOrNull(out bool contextDead)
    {
        contextDead = false;
        uint len = 0;
        int rc = SCardListReaders(_context, null, null, ref len);
        if (IsContextDead(rc)) { contextDead = true; return null; }
        if (rc != 0 || len < 2) return null;
        var buf = new byte[len];
        rc = SCardListReaders(_context, null, buf, ref len);
        if (IsContextDead(rc)) { contextDead = true; return null; }
        if (rc != 0) return null;
        // Multi-string: NUL-separated names, double-NUL terminated.
        var first = System.Text.Encoding.ASCII.GetString(buf).Split('\0', StringSplitOptions.RemoveEmptyEntries);
        return first.Length > 0 ? first[0] : null;
    }

    private byte[]? TryReadUid(string reader)
    {
        int rc = SCardConnect(_context, reader, ShareShared, ProtoT0 | ProtoT1, out var card, out var proto);
        if (rc != 0) return null;
        try
        {
            var ioReq = new ScardIoRequest { Protocol = proto, PciLength = 8 };
            byte[] apdu = { 0xFF, 0xCA, 0x00, 0x00, 0x00 }; // Get Data: UID
            var recv = new byte[64];
            int recvLen = recv.Length;
            rc = SCardTransmit(card, ref ioReq, apdu, apdu.Length, IntPtr.Zero, recv, ref recvLen);
            if (rc != 0 || recvLen < 2) return null;
            // Last two bytes are SW1 SW2; 0x90 0x00 = success.
            if (recv[recvLen - 2] != 0x90 || recv[recvLen - 1] != 0x00) return null;
            return recv.Take(recvLen - 2).ToArray();
        }
        finally
        {
            SCardDisconnect(card, LeaveCard);
        }
    }

    public void Dispose()
    {
        _stop = true;
        _watch?.Join(2000);
        if (_context != IntPtr.Zero) SCardReleaseContext(_context);
    }

    // ---- winscard.dll P/Invoke ----
    private const uint ScopeSystem = 2;
    private const uint ShareShared = 2;
    private const uint ProtoT0 = 1, ProtoT1 = 2;
    private const uint LeaveCard = 0;
    private const uint StateUnaware = 0;
    private const uint StateChanged = 0x2;
    private const uint StatePresent = 0x20;
    private const int ErrTimeout = unchecked((int)0x8010000A);
    private const int ErrNoService = unchecked((int)0x8010001D);
    private const int ErrServiceStopped = unchecked((int)0x8010001E);
    private const int ErrInvalidHandle = unchecked((int)0x80100003);

    /// <summary>Result codes meaning the PC/SC context itself is dead (service
    /// stopped or handle invalidated) — recovery is release + re-establish.</summary>
    private static bool IsContextDead(int rc) =>
        rc is ErrNoService or ErrServiceStopped or ErrInvalidHandle;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    private struct ScardReaderState
    {
        [MarshalAs(UnmanagedType.LPStr)] public string Reader;
        public IntPtr UserData;
        public uint CurrentState;
        public uint EventState;
        public uint AtrLength;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 36)] public byte[] Atr;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ScardIoRequest { public uint Protocol; public uint PciLength; }

    [DllImport("winscard.dll")] private static extern int SCardEstablishContext(uint scope, IntPtr r1, IntPtr r2, out IntPtr context);
    [DllImport("winscard.dll")] private static extern int SCardReleaseContext(IntPtr context);
    [DllImport("winscard.dll", CharSet = CharSet.Ansi)] private static extern int SCardListReaders(IntPtr context, string? groups, byte[]? readers, ref uint len);
    [DllImport("winscard.dll", CharSet = CharSet.Ansi)] private static extern int SCardGetStatusChange(IntPtr context, uint timeoutMs, ref ScardReaderState state, uint count);
    [DllImport("winscard.dll", CharSet = CharSet.Ansi)] private static extern int SCardConnect(IntPtr context, string reader, uint share, uint protocols, out IntPtr card, out uint activeProtocol);
    [DllImport("winscard.dll")] private static extern int SCardDisconnect(IntPtr card, uint disposition);
    [DllImport("winscard.dll")] private static extern int SCardTransmit(IntPtr card, ref ScardIoRequest sendPci, byte[] sendBuf, int sendLen, IntPtr recvPci, byte[] recvBuf, ref int recvLen);
}
