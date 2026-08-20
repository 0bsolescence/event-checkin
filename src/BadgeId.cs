using System.Numerics;

namespace BadgeCheckIn;

/// <summary>
/// Reads a badge UID four ways for the "Show badge id on next tap" diagnostic.
/// Its whole job is to answer one open question — whether a Virtual Keypad
/// <c>External_Number</c> (<c>R_123456</c>) relates to the UID the reader sees —
/// by putting the same bytes on screen in the shapes an access system might
/// have encoded them, so the comparison can be made by eye against a known
/// badge.
///
/// Byte order is the reason there are four: which end a system starts from is
/// exactly the sort of thing that is not written down anywhere, and a UID that
/// looks unrelated big-endian can be the same number backwards.
///
/// Pure, and a line-for-line mirror of the Android twin
/// (android/app/src/main/java/com/example/eventcheckin/BadgeId.kt), which
/// carries the unit tests for these rules.
///
/// Named BadgeId rather than Uid on both sides for one Windows-specific reason:
/// <c>UIElement.Uid</c> is an inherited string property, so inside any WPF
/// control <c>Uid.Describe(...)</c> binds to that property and does not compile.
/// The name is kept identical on Android so the twins still match.
///
/// PRIVACY: this is display only. Nothing here hashes, stores, logs or exports
/// a UID; the caller shows the reading and drops it, and the diagnostic
/// suppresses the check-in flow for that tap so the badge is not enrolled by
/// being read.
/// </summary>
public static class BadgeId
{
    /// <param name="Decimal">The bytes as one unsigned integer, most significant first.</param>
    /// <param name="DecimalReversed">The same bytes read from the other end.</param>
    public sealed record Reading(
        int ByteLength, string Hex, string HexReversed, string Decimal, string DecimalReversed);

    public static Reading Describe(byte[] uid)
    {
        var reversed = uid.Reverse().ToArray();
        return new Reading(
            uid.Length,
            ToHex(uid),
            ToHex(reversed),
            ToDecimal(uid),
            ToDecimal(reversed));
    }

    private static string ToHex(byte[] bytes) => Convert.ToHexString(bytes);

    /// <summary>Unsigned: a UID is a bit pattern, never a negative number, so the
    /// sign bit a signed BigInteger would read is forced positive.</summary>
    private static string ToDecimal(byte[] bytes) =>
        bytes.Length == 0
            ? "0"
            : new BigInteger(bytes, isUnsigned: true, isBigEndian: true).ToString();
}
