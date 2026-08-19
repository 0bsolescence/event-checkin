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
        int rc = SCardEstablishContext(ScopeSystem, IntPtr.Zero, IntPtr.Zero, out _context);
        if (rc != 0) throw new InvalidOperationException($"SCardEstablishContext failed: 0x{rc:X8}");
        _watch = new Thread(WatchLoop) { IsBackground = true, Name = "pcsc-watch" };
        _watch.Start();
    }

    private void WatchLoop()
    {
        string? reader = null;
        bool cardWasPresent = false;

        while (!_stop)
        {
            if (reader is null)
            {
                reader = FirstReaderOrNull();
                if (reader is null)
                {
                    StatusChanged?.Invoke("No reader detected — plug in the USB reader.");
                    Thread.Sleep(1500);
                    continue;
                }
                StatusChanged?.Invoke($"Reader ready: {reader}");
            }

            var state = new ScardReaderState { Reader = reader, CurrentState = StateUnaware };
            int rc = SCardGetStatusChange(_context, 1000, ref state, 1);
            if (rc == ErrTimeout) continue;
            if (rc != 0) { reader = null; cardWasPresent = false; continue; } // reader unplugged etc.

            bool present = (state.EventState & StatePresent) != 0;
            if (present && !cardWasPresent)
            {
                var uid = TryReadUid(reader);
                if (uid is { Length: > 0 }) CardTapped?.Invoke(uid);
                else StatusChanged?.Invoke("Card seen but UID read failed — try again.");
            }
            cardWasPresent = present;
            Thread.Sleep(150); // debounce
        }
    }

    private string? FirstReaderOrNull()
    {
        uint len = 0;
        if (SCardListReaders(_context, null, null, ref len) != 0 || len < 2) return null;
        var buf = new byte[len];
        if (SCardListReaders(_context, null, buf, ref len) != 0) return null;
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
    private const uint StatePresent = 0x20;
    private const int ErrTimeout = unchecked((int)0x8010000A);

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
