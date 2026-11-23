using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace IfboardWin.Services;

/// <summary>
/// 剪贴板同步的密钥推导、认证加密与消息格式；必须与 Android 端 ClipSyncCrypto.kt 逐字节一致。
/// master = PBKDF2-HMAC-SHA256(password, "ifboard-clip-v1|" + user.lower(), 120000, 32)
/// enc = HMAC(master,"enc")，disc = HMAC(master,"disc")，fp = HMAC(master,"fp") 前 4 字节（仅界面核对）。
/// </summary>
public sealed class ClipSyncKeys
{
    public const int Iterations = 120_000;
    private const string SaltPrefix = "ifboard-clip-v1|";

    public byte[] Enc { get; }
    public byte[] Disc { get; }
    public string Fingerprint { get; }

    private ClipSyncKeys(byte[] enc, byte[] disc, string fp)
    {
        Enc = enc; Disc = disc; Fingerprint = fp;
    }

    public static ClipSyncKeys Derive(string user, string password, int iterations = Iterations)
    {
        var salt = Encoding.UTF8.GetBytes(SaltPrefix + user.Trim().ToLowerInvariant());
        var master = Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(password), salt, iterations, HashAlgorithmName.SHA256, 32);
        var enc = Hmac(master, "enc"u8.ToArray());
        var disc = Hmac(master, "disc"u8.ToArray());
        var fp = Convert.ToHexString(Hmac(master, "fp"u8.ToArray()), 0, 4).ToLowerInvariant();
        CryptographicOperations.ZeroMemory(master);
        return new ClipSyncKeys(enc, disc, fp);
    }

    public static byte[] Hmac(byte[] key, byte[] data) => HMACSHA256.HashData(key, data);
}

public static class ClipSyncCrypto
{
    private static readonly byte[] Aad = "IFBC1"u8.ToArray();
    private const int NonceLen = 12;
    private const int TagLen = 16;

    public static byte[] RandomBytes(int n) => RandomNumberGenerator.GetBytes(n);

    /// <summary>nonce(12) || ciphertext || tag(16)，与 Java AES/GCM/NoPadding 输出排布一致</summary>
    public static byte[] Seal(byte[] key, byte[] plain)
    {
        var nonce = RandomBytes(NonceLen);
        var ct = new byte[plain.Length];
        var tag = new byte[TagLen];
        using var gcm = new AesGcm(key, TagLen);
        gcm.Encrypt(nonce, plain, ct, tag, Aad);
        var outBuf = new byte[NonceLen + ct.Length + TagLen];
        nonce.CopyTo(outBuf, 0);
        ct.CopyTo(outBuf, NonceLen);
        tag.CopyTo(outBuf, NonceLen + ct.Length);
        return outBuf;
    }

    /// <summary>解密失败（密钥不同 / 被篡改）返回 null</summary>
    public static byte[]? Open(byte[] key, byte[] sealedData)
    {
        if (sealedData.Length < NonceLen + TagLen) return null;
        try
        {
            var nonce = sealedData.AsSpan(0, NonceLen);
            var ctLen = sealedData.Length - NonceLen - TagLen;
            var ct = sealedData.AsSpan(NonceLen, ctLen);
            var tag = sealedData.AsSpan(NonceLen + ctLen, TagLen);
            var plain = new byte[ctLen];
            using var gcm = new AesGcm(key, TagLen);
            gcm.Decrypt(nonce, ct, tag, plain, Aad);
            return plain;
        }
        catch (CryptographicException)
        {
            return null;
        }
    }
}

/// <summary>明文消息：一行 JSON 头 + '\n' + 正文字节</summary>
public sealed class ClipMessage
{
    public JsonObject Header { get; }
    public byte[] Body { get; }

    public ClipMessage(JsonObject header, byte[]? body = null)
    {
        Header = header;
        Body = body ?? [];
    }

    public string Op => Header["op"]?.GetValue<string>() ?? "";

    public string? Str(string key) => Header[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    public long Long(string key) => Header[key] is JsonValue v && v.TryGetValue<long>(out var l) ? l : 0L;

    public byte[] Encode()
    {
        var head = Encoding.UTF8.GetBytes(Header.ToJsonString());
        var buf = new byte[head.Length + 1 + Body.Length];
        head.CopyTo(buf, 0);
        buf[head.Length] = (byte)'\n';
        Body.CopyTo(buf, head.Length + 1);
        return buf;
    }

    public static ClipMessage? Decode(byte[] plain)
    {
        var nl = Array.IndexOf(plain, (byte)'\n');
        if (nl <= 0) return null;
        try
        {
            var header = JsonNode.Parse(Encoding.UTF8.GetString(plain, 0, nl)) as JsonObject;
            if (header == null) return null;
            return new ClipMessage(header, plain.AsSpan(nl + 1).ToArray());
        }
        catch
        {
            return null;
        }
    }

    /// <summary>多条内容打包（从旧到新）：每条 = 4 字节大端长度 + 该条 Encode() 的字节</summary>
    public static byte[] EncodeBatch(IEnumerable<ClipMessage> items)
    {
        using var ms = new MemoryStream();
        foreach (var it in items)
        {
            var b = it.Encode();
            var len = new byte[4];
            System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(len, b.Length);
            ms.Write(len);
            ms.Write(b);
        }
        return ms.ToArray();
    }

    public static List<ClipMessage> DecodeBatch(byte[] body)
    {
        var list = new List<ClipMessage>();
        var off = 0;
        while (off + 4 <= body.Length)
        {
            var n = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(body.AsSpan(off, 4));
            off += 4;
            if (n <= 0 || off + n > body.Length) break;
            var m = Decode(body.AsSpan(off, n).ToArray());
            if (m != null) list.Add(m);
            off += n;
        }
        return list;
    }

    public static ClipMessage Of(string op, IEnumerable<KeyValuePair<string, object?>>? fields = null, byte[]? body = null)
    {
        var h = new JsonObject { ["op"] = op };
        if (fields != null)
            foreach (var (k, v) in fields)
                h[k] = v switch
                {
                    null => null,
                    string s => JsonValue.Create(s),
                    long l => JsonValue.Create(l),
                    int i => JsonValue.Create(i),
                    bool b => JsonValue.Create(b),
                    _ => JsonValue.Create(v.ToString())
                };
        return new ClipMessage(h, body);
    }
}
