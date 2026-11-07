package com.langamestudio.lanpartyarcade.network;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Creates host-only session identities in AndroidKeyStore and pins clients to their fingerprint. */
public final class TlsSessions {
    private TlsSessions() { }

    public static HostIdentity createHostIdentity(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);

        long now = System.currentTimeMillis();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=LAN Arcade Session"))
                .setCertificateSerialNumber(BigInteger.valueOf(now).abs())
                .setCertificateNotBefore(new Date(now - 60_000L))
                .setCertificateNotAfter(new Date(now + 24L * 60L * 60L * 1000L))
                .setUserAuthenticationRequired(false)
                .build();
        generator.initialize(specification);
        generator.generateKeyPair();

        keyStore.load(null);
        Certificate certificate = keyStore.getCertificate(alias);
        if (!(certificate instanceof X509Certificate)) throw new IllegalStateException("AndroidKeyStore did not create an X.509 certificate");
        X509Certificate x509 = (X509Certificate) certificate;
        x509.checkValidity();

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        return new HostIdentity(context, fingerprint(x509), alias);
    }

    public static SSLContext createPinnedClientContext(String expectedFingerprint) throws Exception {
        final byte[] expected = decodeHex(expectedFingerprint);
        TrustManager[] managers = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not accepted");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing host certificate");
                chain[0].checkValidity();
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(expected, actual)) throw new CertificateException("Host certificate fingerprint mismatch");
                } catch (CertificateException invalid) {
                    throw invalid;
                } catch (Exception failure) {
                    throw new CertificateException("Unable to verify host certificate", failure);
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers, new SecureRandom());
        return context;
    }

    public static String safetyCode(SSLSession session) throws Exception {
        byte[] sessionId = session.getId();
        if (sessionId == null || sessionId.length < 16) throw new CertificateException("TLS session has no usable channel identifier");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId);
        return String.format(java.util.Locale.ROOT, "%02X%02X%02X", digest[0] & 0xff, digest[1] & 0xff, digest[2] & 0xff);
    }

    public static void enableSupportedSecureProtocols(javax.net.ssl.SSLSocket socket) {
        socket.setEnabledProtocols(supportedProtocols(socket.getSupportedProtocols()));
    }

    public static void enableSupportedSecureProtocols(javax.net.ssl.SSLServerSocket socket) {
        socket.setEnabledProtocols(supportedProtocols(socket.getSupportedProtocols()));
    }

    private static String[] supportedProtocols(String[] supported) {
        boolean tls13 = Arrays.asList(supported).contains("TLSv1.3");
        boolean tls12 = Arrays.asList(supported).contains("TLSv1.2");
        if (tls13 && tls12) return new String[]{"TLSv1.3", "TLSv1.2"};
        if (tls13) return new String[]{"TLSv1.3"};
        if (tls12) return new String[]{"TLSv1.2"};
        throw new IllegalStateException("No supported TLS 1.2+ protocol");
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
        return result.toString();
    }

    private static byte[] decodeHex(String hex) throws CertificateException {
        if (hex == null || hex.length() != 64) throw new CertificateException("Invalid certificate fingerprint");
        byte[] result = new byte[32];
        try {
            for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException invalid) {
            throw new CertificateException("Invalid certificate fingerprint", invalid);
        }
        return result;
    }

    public static final class HostIdentity {
        public final SSLContext context;
        public final String fingerprint;
        public final String keyAlias;
        private HostIdentity(SSLContext context, String fingerprint, String keyAlias) {
            this.context = context; this.fingerprint = fingerprint; this.keyAlias = keyAlias;
        }
    }
}
