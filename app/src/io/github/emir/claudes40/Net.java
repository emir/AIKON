package io.github.emir.claudes40;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

import javax.microedition.io.ConnectionNotFoundException;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.HttpsConnection;
import javax.microedition.io.SecurityInfo;
import javax.microedition.pki.Certificate;
import javax.microedition.pki.CertificateException;

/**
 * One HTTPS request, blocking; always called from a worker thread.
 *
 * Only "https://" URLs are accepted. There is no HTTP fallback and no way to
 * relax certificate checks: those are done by the phone's own TLS stack.
 *
 * Failures are classified by the phase they happen in, because MIDP gives
 * no portable DNS/TCP/TLS error codes:
 *   OPEN      Connector.open / setup          -> nothing was sent
 *   SEND      writing the request body         -> may or may not have arrived
 *   RESPONSE  waiting for status/headers       -> request probably arrived
 *   READ      reading the body                 -> request arrived
 */
final class Net {

    static final int PHASE_OPEN = 0;
    static final int PHASE_SEND = 1;
    static final int PHASE_RESPONSE = 2;
    static final int PHASE_READ = 3;
    static final int PHASE_DONE = 4;

    static final int ERR_NONE = 0;
    static final int ERR_NOT_HTTPS = 1;
    static final int ERR_PERMISSION = 2;
    static final int ERR_NO_CONNECTION_TYPE = 3;
    static final int ERR_CERTIFICATE = 4;
    static final int ERR_IO = 5;
    static final int ERR_ENCODING = 6;

    /** Largest response body kept in RAM. */
    static final int MAX_BODY = 8192;
    /** Rough size of the server's response headers (DataUsage). */
    private static final int RESPONSE_HEADERS = 200;

    static final class Result {
        int error = ERR_NONE;
        int phase = PHASE_OPEN;
        int httpCode = -1;
        String detail = "";
        /** Body was longer than MAX_BODY and was cut. */
        boolean bodyCut;
        String body;
        S40Message msg;
        String tls = "";

        boolean ok() {
            return error == ERR_NONE;
        }

        /** The request may have reached the server. */
        boolean maybeDelivered() {
            return phase >= PHASE_SEND;
        }
    }

    /** Progress callback, called on the worker thread. */
    interface Listener {
        void phase(int phase);
    }

    private Net() {
    }

    static boolean isHttps(String url) {
        return url != null && url.startsWith("https://") && url.length() > 8;
    }

    static Result request(String url, String method, String auth, String body, String userAgent,
            Listener listener) {
        byte[] payload = null;
        if (body != null) {
            try {
                payload = body.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                Result r = new Result();
                r.error = ERR_ENCODING;
                r.detail = L.s("UTF-8 desteklenmiyor", "UTF-8 not supported");
                return r;
            }
        }
        return send(url, method, auth, payload, "text/plain; charset=utf-8", userAgent, listener);
    }

    /** POSTs binary data (a voice clip) with the given Content-Type; the reply is S40/1 as usual. */
    static Result upload(String url, String auth, byte[] data, String type, String userAgent, Listener listener) {
        return send(url, "POST", auth, data, type, userAgent, listener);
    }

    private static Result send(String url, String method, String auth, byte[] payload, String type,
            String userAgent, Listener listener) {
        Result r = new Result();
        if (!isHttps(url)) {
            r.error = ERR_NOT_HTTPS;
            r.detail = L.s("Adres https:// ile başlamalı", "The address must start with https://");
            return r;
        }
        HttpConnection c = null;
        InputStream in = null;
        OutputStream out = null;
        int received = 0;
        try {
            c = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "text/plain");
            c.setRequestProperty("Cache-Control", "no-store");
            if (auth != null) {
                c.setRequestProperty("Authorization", "Bearer " + auth);
            }
            if (payload != null) {
                c.setRequestProperty("Content-Type", type);
                c.setRequestProperty("Content-Length", String.valueOf(payload.length));
                r.phase = PHASE_SEND;
                report(listener, r.phase);
                out = c.openOutputStream();
                out.write(payload);
                // no flush(): some S40 stacks switch to chunked encoding on flush
                out.close();
                out = null;
            }
            r.phase = PHASE_RESPONSE;
            report(listener, r.phase);
            r.httpCode = c.getResponseCode();
            if (c instanceof HttpsConnection) {
                r.tls = describe(((HttpsConnection) c).getSecurityInfo());
            }
            r.phase = PHASE_READ;
            in = c.openInputStream();
            byte[] buf = new byte[MAX_BODY];
            int n = 0;
            while (n < MAX_BODY) {
                int k = in.read(buf, n, MAX_BODY - n);
                if (k < 0) {
                    break;
                }
                n += k;
                received = n;
            }
            if (n == MAX_BODY && in.read() >= 0) {
                r.bodyCut = true;
                received = n + 1;
            }
            int end = r.bodyCut ? utf8Boundary(buf, n) : n;
            try {
                r.body = new String(buf, 0, end, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                r.error = ERR_ENCODING;
                r.detail = L.s("UTF-8 desteklenmiyor", "UTF-8 not supported");
                return r;
            }
            r.msg = S40Message.parse(r.body);
            r.phase = PHASE_DONE;
        } catch (SecurityException e) {
            r.error = ERR_PERMISSION;
            r.detail = L.s("Java ağ izni verilmedi", "Java network access denied");
        } catch (ConnectionNotFoundException e) {
            r.error = ERR_NO_CONNECTION_TYPE;
            r.detail = L.s("Bağlantı türü desteklenmiyor: ", "Connection type not supported: ") + e.getMessage();
        } catch (CertificateException e) {
            r.error = ERR_CERTIFICATE;
            // S60 reports TLS alerts as CertificateException too; its message
            // carries the Symbian error code (e.g. -7547), so keep a message
            // with a number in it (MIDP's default text only repeats the reason)
            String m = e.getMessage();
            boolean code = false;
            for (int i = 0; m != null && i < m.length() && !code; i++) {
                code = Character.isDigit(m.charAt(i));
            }
            r.detail = L.s("Sertifika: ", "Certificate: ") + certReason(e.getReason()) + (code ? " [" + m + "]" : "")
                    + certSubject(e.getCertificate());
        } catch (IOException e) {
            r.error = ERR_IO;
            r.detail = e.getClass().getName() + ": " + e.getMessage();
        } catch (RuntimeException e) {
            r.error = ERR_IO;
            r.detail = e.getClass().getName() + ": " + e.getMessage();
        } finally {
            close(in, out, c);
            count(r, url, method, auth, userAgent, payload, received);
        }
        return r;
    }

    /**
     * Adds the request to DataUsage once something may have gone over the
     * network: the body exactly, headers estimated from what we send plus a
     * margin for the phone's own, the response headers as a fixed guess.
     */
    private static void count(Result r, String url, String method, String auth, String userAgent, byte[] payload,
            int received) {
        if (r.phase < PHASE_SEND && r.httpCode < 0) {
            return; // not connected: nothing sent (MIDP connects in getResponseCode)
        }
        int headers = 160 + url.length() + method.length() + userAgent.length() + (auth == null ? 0 : auth.length() + 24);
        int sent = headers + (payload == null ? 0 : payload.length + 60);
        DataUsage.add(sent, r.httpCode < 0 ? 0 : RESPONSE_HEADERS + received);
    }

    private static void report(Listener l, int phase) {
        if (l != null) {
            l.phase(phase);
        }
    }

    private static void close(InputStream in, OutputStream out, HttpConnection c) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            // ignore
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            // ignore
        }
        try {
            if (c != null) {
                c.close();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    /** Length of the longest prefix that does not end inside a UTF-8 sequence. */
    static int utf8Boundary(byte[] b, int n) {
        int i = n;
        int back = 0;
        while (i > 0 && back < 4 && (b[i - 1] & 0xC0) == 0x80) {
            i--;
            back++;
        }
        if (i == 0) {
            return n;
        }
        int lead = b[i - 1] & 0xFF;
        int need = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
        return need > back ? i - 1 : n;
    }

    static String describe(SecurityInfo si) {
        if (si == null) {
            return "";
        }
        StringBuffer sb = new StringBuffer();
        sb.append(si.getProtocolName()).append(' ').append(si.getProtocolVersion());
        sb.append(L.s("\nŞifre: ", "\nCipher: ")).append(si.getCipherSuite());
        Certificate cert = si.getServerCertificate();
        if (cert != null) {
            sb.append(L.s("\nSertifika: ", "\nCertificate: ")).append(cert.getSubject());
            sb.append(L.s("\nVeren: ", "\nIssuer: ")).append(cert.getIssuer());
            sb.append(L.s("\nİmza: ", "\nSignature: ")).append(cert.getSigAlgName());
            sb.append(L.s("\nGeçerlilik sonu: ", "\nValid until: ")).append(Text.date(cert.getNotAfter()));
        }
        return sb.toString();
    }

    private static String certSubject(Certificate c) {
        if (c == null) {
            return "";
        }
        return L.s("\nKonu: ", "\nSubject: ") + c.getSubject() + L.s("\nVeren: ", "\nIssuer: ") + c.getIssuer();
    }

    static String certReason(byte reason) {
        switch (reason) {
        case CertificateException.UNRECOGNIZED_ISSUER:
            return L.s("tanınmayan sertifika otoritesi (telefonda kök sertifika yok)", "unknown certificate authority (root not on the phone)");
        case CertificateException.EXPIRED:
            // S60 also reports a not-yet-valid certificate as EXPIRED
            return L.s("süresi dolmuş veya henüz geçerli değil (telefonun tarihi / saat dilimi?)",
                    "expired or not yet valid (phone date / time zone?)");
        case CertificateException.NOT_YET_VALID:
            return L.s("henüz geçerli değil (telefon saati?)", "not yet valid (phone clock?)");
        case CertificateException.SITENAME_MISMATCH:
            return L.s("alan adı uyuşmuyor", "host name mismatch");
        case CertificateException.BROKEN_CHAIN:
            return L.s("sertifika zinciri eksik", "broken certificate chain");
        case CertificateException.ROOT_CA_EXPIRED:
            return L.s("kök sertifikanın süresi dolmuş", "root certificate expired");
        case CertificateException.VERIFICATION_FAILED:
            return L.s("imza doğrulanamadı (kök sertifika eksik veya algoritma desteklenmiyor)", "signature not verified (root missing or algorithm unsupported)");
        case CertificateException.MISSING_SIGNATURE:
            return L.s("imza yok", "missing signature");
        case CertificateException.BAD_EXTENSIONS:
            return L.s("desteklenmeyen uzantı", "unsupported extension");
        case CertificateException.INAPPROPRIATE_KEY_USAGE:
            return L.s("uygunsuz anahtar kullanımı", "inappropriate key usage");
        default:
            return L.s("kod ", "code ") + reason;
        }
    }

    /** Short user-facing explanation of a failed request. */
    static String explain(Result r) {
        switch (r.error) {
        case ERR_NOT_HTTPS:
            return L.s("Sunucu adresi https:// ile başlamalı.", "The server address must start with https://.");
        case ERR_PERMISSION:
            return L.s("Java ağ izni verilmedi. Uygulama ayarlarından ağ erişimine izin verin.", "Java network access was denied. Allow it in the application settings.");
        case ERR_NO_CONNECTION_TYPE:
            return L.s("HTTPS bağlantısı açılamadı. Java erişim noktası (mobil veri / WLAN) ayarını kontrol edin.", "Could not open HTTPS. Check the Java access point (mobile data / WLAN).");
        case ERR_CERTIFICATE:
            return L.s("Güvenli bağlantı kurulamadı (sertifika/TLS). ", "Secure connection failed (certificate/TLS). ") + r.detail;
        case ERR_ENCODING:
            return L.s("Karakter kodlaması hatası: ", "Character encoding error: ") + r.detail;
        case ERR_IO:
            // MIDP stacks usually connect (DNS, TCP, TLS) only inside
            // getResponseCode(), so no HTTP status yet means the connection
            // itself may have failed
            if (r.httpCode < 0) {
                return L.s("Bağlantı kurulamadı veya yanıt gelmedi (erişim noktası, DNS, ağ veya TLS). ", "Could not connect or no reply (access point, DNS, network or TLS). ")
                        + hint(r.detail) + r.detail;
            }
            return L.s("Yanıt okunamadı (HTTP ", "Could not read the reply (HTTP ") + r.httpCode + "). " + hint(r.detail) + r.detail;
        default:
            return "";
        }
    }

    private static String hint(String d) {
        String s = d == null ? "" : d.toLowerCase();
        if (s.indexOf("dns") >= 0 || s.indexOf("host") >= 0 || s.indexOf("resolv") >= 0) {
            return L.s("(DNS olabilir) ", "(maybe DNS) ");
        }
        if (s.indexOf("timeout") >= 0 || s.indexOf("timed out") >= 0) {
            return L.s("(zaman aşımı) ", "(timeout) ");
        }
        if (s.indexOf("ssl") >= 0 || s.indexOf("tls") >= 0 || s.indexOf("handshake") >= 0
                || s.indexOf("cert") >= 0) {
            return L.s("(TLS olabilir) ", "(maybe TLS) ");
        }
        return "";
    }
}
