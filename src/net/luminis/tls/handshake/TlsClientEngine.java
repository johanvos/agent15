/*
 * Copyright © 2020, 2021, 2022, 2023 Peter Doornbosch
 *
 * This file is part of Agent15, an implementation of TLS 1.3 in Java.
 *
 * Agent15 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Agent15 is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.tls.handshake;

import net.luminis.tls.*;
import net.luminis.tls.alert.*;
import net.luminis.tls.extension.Extension;
import net.luminis.tls.extension.*;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.luminis.tls.TlsConstants.SignatureScheme.*;


public class TlsClientEngine extends TlsEngine implements ClientMessageProcessor {

    public static final List<TlsConstants.SignatureScheme> AVAILABLE_SIGNATURES = List.of(
            rsa_pss_rsae_sha256,
            rsa_pss_rsae_sha384,
            rsa_pss_rsae_sha512,
            ecdsa_secp256r1_sha256);

    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    enum Status {
        Initial,
        ClientHelloSent,
        ServerHelloReceived,
        EncryptedExtensionsReceived,
        CertificateRequestReceived,
        CertificateReceived,
        CertificateVerifyReceived,
        Finished
    }

    private final ClientMessageSender sender;
    private final TlsStatusEventHandler statusHandler;
    private String serverName;
    private boolean compatibilityMode;
    private List<TlsConstants.CipherSuite> supportedCiphers;
    private TlsConstants.CipherSuite selectedCipher;
    private List<Extension> requestedExtensions;
    private List<Extension> sentExtensions;
    private Status status = Status.Initial;
    private ClientHello clientHello;
    private TranscriptHash transcriptHash;
    private List<TlsConstants.SignatureScheme> supportedSignatures;
    private X509Certificate serverCertificate;
    private List<X509Certificate> serverCertificateChain = Collections.emptyList();
    private X509TrustManager customTrustManager;
    private NewSessionTicket newSessionTicket;
    private HostnameVerifier hostnameVerifier;
    private List<NewSessionTicket> obtainedNewSessionTickets;
    private boolean pskAccepted = false;
    private boolean clientAuthRequested;
    private List<X500Principal> clientCertificateAuthorities;
    private Function<List<X500Principal>, CertificateWithPrivateKey> clientCertificateSelector;
    private List<TlsConstants.SignatureScheme> serverSupportedSignatureSchemes;


    public TlsClientEngine(ClientMessageSender clientMessageSender, TlsStatusEventHandler tlsStatusHandler) {
        sender = clientMessageSender;
        statusHandler = tlsStatusHandler;
        supportedCiphers = new ArrayList<>();
        requestedExtensions = new ArrayList<>();
        hostnameVerifier = new DefaultHostnameVerifier();
        obtainedNewSessionTickets = new ArrayList<>();
        clientCertificateSelector = l -> null;
    }

    public void startHandshake() throws IOException {
        startHandshake(TlsConstants.NamedGroup.secp256r1, List.of(rsa_pss_rsae_sha256, ecdsa_secp256r1_sha256));
    }

    public void startHandshake(TlsConstants.NamedGroup ecCurve) throws IOException {
        startHandshake(ecCurve, List.of(rsa_pss_rsae_sha256));
    }

    public void startHandshake(TlsConstants.NamedGroup ecCurve, List<TlsConstants.SignatureScheme> signatureSchemes) throws IOException {
        if (signatureSchemes.stream().anyMatch(scheme -> !AVAILABLE_SIGNATURES.contains(scheme))) {
            // Remove available leaves the ones that are not available (cannot be supported)
            var unsupportedSignatures = new ArrayList<>(signatureSchemes);
            unsupportedSignatures.removeAll(AVAILABLE_SIGNATURES);
            throw new IllegalArgumentException("Unsupported signature scheme(s): " + unsupportedSignatures);
        }
        if (newSessionTicket != null && !supportedCiphers.contains(newSessionTicket.getCipher())) {
            throw new IllegalStateException("For session resumption, support ciphers should contain the cipher used with the session-to-resume (" + newSessionTicket.getCipher().toString() + ")");
        }

        supportedSignatures = signatureSchemes;
        generateKeys(ecCurve);
        if (serverName == null || supportedCiphers.isEmpty()) {
            throw new IllegalStateException("not all mandatory properties are set");
        }

        List<Extension> extensions;
        if (newSessionTicket != null) {
            extensions = new ArrayList<>();
            extensions.addAll(requestedExtensions);
            extensions.add(new ClientHelloPreSharedKeyExtension(newSessionTicket));

            TlsConstants.CipherSuite cipher = newSessionTicket.getCipher();
            transcriptHash = new TranscriptHash(hashLength(cipher));
            state = new TlsState(transcriptHash, newSessionTicket.getPSK(), keyLength(cipher), hashLength(cipher));
        }
        else {
            extensions = requestedExtensions;
            // Defer initialization of TlsState until selected cipher is known.
        }

        clientHello = new ClientHello(serverName, publicKey, compatibilityMode, supportedCiphers, supportedSignatures,
                ecCurve, extensions, state, ClientHello.PskKeyEstablishmentMode.PSKwithDHE);
        sentExtensions = clientHello.getExtensions();

        if (state != null) {
            transcriptHash.record(clientHello);
            state.computeEarlyTrafficSecret();
            statusHandler.earlySecretsKnown();
        }
        sender.send(clientHello);
        status = Status.ClientHelloSent;
    }

    /**
     * Updates the (handshake) state with a received Server Hello message.
     * @param serverHello
     * @param protectedBy
     * @throws MissingExtensionAlert
     */
    @Override
    public void received(ServerHello serverHello, ProtectionKeysType protectedBy) throws MissingExtensionAlert, IllegalParameterAlert {
        boolean containsSupportedVersionExt = serverHello.getExtensions().stream().anyMatch(ext -> ext instanceof SupportedVersionsExtension);
        boolean containsKeyExt = serverHello.getExtensions().stream().anyMatch(ext -> ext instanceof PreSharedKeyExtension || ext instanceof KeyShareExtension);
        // https://tools.ietf.org/html/rfc8446#section-4.1.3
        // "All TLS 1.3 ServerHello messages MUST contain the "supported_versions" extension.
        // Current ServerHello messages additionally contain either the "pre_shared_key" extension or the "key_share"
        // extension, or both (when using a PSK with (EC)DHE key establishment)."
        if (! containsSupportedVersionExt || !containsKeyExt) {
            throw new MissingExtensionAlert();
        }

        // https://tools.ietf.org/html/rfc8446#section-4.2.1
        // "A server which negotiates TLS 1.3 MUST respond by sending a "supported_versions" extension containing the selected version value (0x0304)."
        short tlsVersion = serverHello.getExtensions().stream()
                .filter(extension -> extension instanceof SupportedVersionsExtension)
                .map(extension -> ((SupportedVersionsExtension) extension).getTlsVersion())
                .findFirst()
                .get();
        if (tlsVersion != 0x0304) {
            throw new IllegalParameterAlert("invalid tls version");
        }

        // https://tools.ietf.org/html/rfc8446#section-4.2
        // "If an implementation receives an extension which it recognizes and which is not specified for the message in
        // which it appears, it MUST abort the handshake with an "illegal_parameter" alert."
        if (serverHello.getExtensions().stream()
            .anyMatch(ext -> ! (ext instanceof SupportedVersionsExtension) &&
                    ! (ext instanceof PreSharedKeyExtension) &&
                    ! (ext instanceof KeyShareExtension))) {
            throw new IllegalParameterAlert("illegal extension in server hello");
        }

        Optional<KeyShareExtension.KeyShareEntry> keyShare = serverHello.getExtensions().stream()
                .filter(extension -> extension instanceof KeyShareExtension)
                // In the context of a server hello, the key share extension contains exactly one key share entry
                .map(extension -> ((KeyShareExtension) extension).getKeyShareEntries().get(0))
                .findFirst();

        Optional<Extension> preSharedKey = serverHello.getExtensions().stream()
                .filter(extension -> extension instanceof ServerPreSharedKeyExtension)
                .findFirst();

        // https://tools.ietf.org/html/rfc8446#section-4.1.3
        // "ServerHello messages additionally contain either the "pre_shared_key" extension or the "key_share" extension,
        // or both (when using a PSK with (EC)DHE key establishment)."
        if (keyShare.isEmpty() && preSharedKey.isEmpty()) {
            throw new MissingExtensionAlert(" either the pre_shared_key extension or the key_share extension must be present");
        }

        if (preSharedKey.isPresent()) {
            // https://tools.ietf.org/html/rfc8446#section-4.2.11
            // "In order to accept PSK key establishment, the server sends a "pre_shared_key" extension indicating the selected identity."
            pskAccepted = true;
        }

        if (! supportedCiphers.contains(serverHello.getCipherSuite())) {
            // https://tools.ietf.org/html/rfc8446#section-4.1.3
            // "A client which receives a cipher suite that was not offered MUST abort the handshake with an "illegal_parameter" alert."
            throw new IllegalParameterAlert("cipher suite does not match");
        }
        selectedCipher = serverHello.getCipherSuite();

        if (state == null) {
            transcriptHash = new TranscriptHash(hashLength(selectedCipher));
            state = new TlsState(transcriptHash, keyLength(selectedCipher), hashLength(selectedCipher));
            transcriptHash.record(clientHello);
            state.computeEarlyTrafficSecret();
            statusHandler.earlySecretsKnown();
        }

        if (preSharedKey.isPresent()) {
            state.setPskSelected(((ServerPreSharedKeyExtension) preSharedKey.get()).getSelectedIdentity());
            Logger.debug("Server has accepted PSK key establishment");
        }
        else {
            state.setNoPskSelected();
        }
        if (keyShare.isPresent()) {
            state.setOwnKey(privateKey);
            state.setPeerKey(keyShare.get().getKey());
            state.computeSharedSecret();
        }
        transcriptHash.record(serverHello);
        state.computeHandshakeSecrets();
        status = Status.ServerHelloReceived;
        statusHandler.handshakeSecretsKnown();
    }

    @Override
    public void received(EncryptedExtensions encryptedExtensions, ProtectionKeysType protectedBy) throws TlsProtocolException {
        if (protectedBy != ProtectionKeysType.Handshake) {
            throw new UnexpectedMessageAlert("incorrect protection level");
        }
        if (status != Status.ServerHelloReceived) {
            // https://tools.ietf.org/html/rfc8446#section-4.3.1
            // "the server MUST send the EncryptedExtensions message immediately after the ServerHello message"
            throw new UnexpectedMessageAlert("unexpected encrypted extensions message");
        }

        List<Class> clientExtensionTypes = sentExtensions.stream()
                .map(extension -> extension.getClass()).collect(Collectors.toList());
        boolean allClientResponses = encryptedExtensions.getExtensions().stream()
                .filter(ext -> ! (ext instanceof UnknownExtension))
                .allMatch(ext -> clientExtensionTypes.contains(ext.getClass()));
        if (! allClientResponses) {
            // https://tools.ietf.org/html/rfc8446#section-4.2
            // "Implementations MUST NOT send extension responses if the remote endpoint did not send the corresponding
            // extension requests, with the exception of the "cookie" extension in the HelloRetryRequest. Upon receiving
            // such an extension, an endpoint MUST abort the handshake with an "unsupported_extension" alert."
            throw new UnsupportedExtensionAlert("extension response to missing request");
        }

        int uniqueExtensions = encryptedExtensions.getExtensions().stream()
                .map(extension -> extension.getClass())
                .collect(Collectors.toSet())
                .size();
        if (uniqueExtensions != encryptedExtensions.getExtensions().size()) {
            // "There MUST NOT be more than one extension of the same type in a given extension block."
            throw new UnsupportedExtensionAlert("duplicate extensions not allowed");
        }

        transcriptHash.record(encryptedExtensions);
        status = Status.EncryptedExtensionsReceived;
        statusHandler.extensionsReceived(encryptedExtensions.getExtensions());
    }

    @Override
    public void received(CertificateMessage certificateMessage, ProtectionKeysType protectedBy) throws TlsProtocolException {
        if (protectedBy != ProtectionKeysType.Handshake) {
            throw new UnexpectedMessageAlert("incorrect protection level");
        }
        if (status != Status.EncryptedExtensionsReceived && status != Status.CertificateRequestReceived) {
            // https://tools.ietf.org/html/rfc8446#section-4.4
            // "TLS generally uses a common set of messages for authentication, key confirmation, and handshake
            //   integrity: Certificate, CertificateVerify, and Finished.  (...)  These three messages are always
            //   sent as the last messages in their handshake flight."
            throw new UnexpectedMessageAlert("unexpected certificate message");
        }

        if (certificateMessage.getRequestContext().length > 0) {
            // https://tools.ietf.org/html/rfc8446#section-4.4.2
            // "If this message is in response to a CertificateRequest, the value of certificate_request_context in that
            // message. Otherwise (in the case of server authentication), this field SHALL be zero length."
            throw new IllegalParameterAlert("certificate request context should be zero length");
        }
        if (certificateMessage.getEndEntityCertificate() == null) {
            throw new IllegalParameterAlert("missing certificate");
        }

        serverCertificate = certificateMessage.getEndEntityCertificate();
        serverCertificateChain = certificateMessage.getCertificateChain();
        transcriptHash.recordServer(certificateMessage);
        status = Status.CertificateReceived;
    }

    @Override
    public void received(CertificateVerifyMessage certificateVerifyMessage, ProtectionKeysType protectedBy) throws TlsProtocolException {
        if (protectedBy != ProtectionKeysType.Handshake) {
            throw new UnexpectedMessageAlert("incorrect protection level");
        }
        if (status != Status.CertificateReceived) {
            // https://tools.ietf.org/html/rfc8446#section-4.4.3
            // "When sent, this message MUST appear immediately after the Certificate message and immediately prior to
            // the Finished message."
            throw new UnexpectedMessageAlert("unexpected certificate verify message");
        }

        TlsConstants.SignatureScheme signatureScheme = certificateVerifyMessage.getSignatureScheme();
        if (!supportedSignatures.contains(signatureScheme)) {
            // https://tools.ietf.org/html/rfc8446#section-4.4.3
            // "If the CertificateVerify message is sent by a server, the signature algorithm MUST be one offered in
            // the client's "signature_algorithms" extension"
            throw new IllegalParameterAlert("signature scheme does not match");
        }

        byte[] signature = certificateVerifyMessage.getSignature();
        if (!verifySignature(signature, signatureScheme, serverCertificate, transcriptHash.getServerHash(TlsConstants.HandshakeType.certificate))) {
            throw new DecryptErrorAlert("signature verification fails");
        }

        // Now the certificate signature has been validated, check the certificate validity
        checkCertificateValidity(serverCertificateChain);
        if (!hostnameVerifier.verify(serverName, serverCertificate)) {
            throw new CertificateUnknownAlert("servername does not match");
        }

        transcriptHash.recordServer(certificateVerifyMessage);
        status = Status.CertificateVerifyReceived;
    }

    @Override
    public void received(FinishedMessage finishedMessage, ProtectionKeysType protectedBy) throws ErrorAlert, IOException {
        if (protectedBy != ProtectionKeysType.Handshake) {
            throw new UnexpectedMessageAlert("incorrect protection level");
        }
        Status expectedStatus;
        if (pskAccepted) {
            expectedStatus = Status.EncryptedExtensionsReceived;
        }
        else {
            expectedStatus = Status.CertificateVerifyReceived;
        }
        if (status != expectedStatus) {
            throw new UnexpectedMessageAlert("unexpected finished message");
        }

        transcriptHash.recordServer(finishedMessage);

        // https://tools.ietf.org/html/rfc8446#section-4.4
        // "   | Mode      | Handshake Context       | Base Key                    |
        //     +-----------+-------------------------+-----------------------------+
        //     | Server    | ClientHello ... later   | server_handshake_traffic_   |
        //     |           | of EncryptedExtensions/ | secret                      |
        //     |           | CertificateRequest      |                             |"
        // https://datatracker.ietf.org/doc/html/rfc8446#section-4.4.4
        // "The verify_data value is computed as follows:
        //   verify_data = HMAC(finished_key, Transcript-Hash(Handshake Context, Certificate*, CertificateVerify*))
        //      * Only included if present."
        byte[] serverHmac = computeFinishedVerifyData(transcriptHash.getServerHash(TlsConstants.HandshakeType.certificate_verify), state.getServerHandshakeTrafficSecret());
        // https://tools.ietf.org/html/rfc8446#section-4.4
        // "Recipients of Finished messages MUST verify that the contents are correct and if incorrect MUST terminate the connection with a "decrypt_error" alert."
        if (!Arrays.equals(finishedMessage.getVerifyData(), serverHmac)) {
            throw new DecryptErrorAlert("incorrect finished message");
        }

        if (clientAuthRequested) {
            sendClientAuth();
        }

        // https://tools.ietf.org/html/rfc8446#section-4.4
        // "   | Mode      | Handshake Context       | Base Key                    |
        //     | Client    | ClientHello ... later   | client_handshake_traffic_   |
        //     |           | of server               | secret                      |
        //     |           | Finished/EndOfEarlyData |                             |"
        // https://datatracker.ietf.org/doc/html/rfc8446#section-4.4.4
        // "The verify_data value is computed as follows:
        //   verify_data = HMAC(finished_key, Transcript-Hash(Handshake Context, Certificate*, CertificateVerify*))
        //      * Only included if present."
        byte[] clientHmac = computeFinishedVerifyData(transcriptHash.getClientHash(TlsConstants.HandshakeType.certificate_verify), state.getClientHandshakeTrafficSecret());
        FinishedMessage clientFinished = new FinishedMessage(clientHmac);
        sender.send(clientFinished);

        transcriptHash.recordClient(clientFinished);
        state.computeApplicationSecrets();
        state.computeResumptionMasterSecret();
        status = Status.Finished;
        statusHandler.handshakeFinished();
    }

    @Override
    public void received(NewSessionTicketMessage nst, ProtectionKeysType protectedBy) throws UnexpectedMessageAlert {
        if (protectedBy != ProtectionKeysType.Application) {
            throw new UnexpectedMessageAlert("incorrect protection level");
        }
        NewSessionTicket ticket = new NewSessionTicket(state, nst, selectedCipher);
        obtainedNewSessionTickets.add(ticket);
        statusHandler.newSessionTicketReceived(ticket);
    }

    @Override
    public void received(CertificateRequestMessage certificateRequestMessage, ProtectionKeysType protectedBy) throws TlsProtocolException, IOException {
        if (protectedBy != ProtectionKeysType.Handshake) {
            throw new UnexpectedMessageAlert("incorrect protection level");
        }
        if (status != Status.EncryptedExtensionsReceived) {
            throw new UnexpectedMessageAlert("unexpected certificate request message");
        }

        serverSupportedSignatureSchemes = certificateRequestMessage.getExtensions().stream()
                .filter(extension -> extension instanceof SignatureAlgorithmsExtension)
                .findFirst()
                .map(extension -> ((SignatureAlgorithmsExtension) extension).getSignatureAlgorithms())
                // https://datatracker.ietf.org/doc/html/rfc8446#section-4.3.2
                // "The "signature_algorithms" extension MUST be specified..."
                .orElseThrow(() -> new MissingExtensionAlert());

        transcriptHash.record(certificateRequestMessage);

        clientCertificateAuthorities = certificateRequestMessage.getExtensions().stream()
                .filter(extension -> extension instanceof CertificateAuthoritiesExtension)
                .findFirst()
                .map(extension -> ((CertificateAuthoritiesExtension) extension).getAuthorities())
                .orElse(Collections.emptyList());
        clientAuthRequested = true;

        status = Status.CertificateRequestReceived;
    }

    protected boolean verifySignature(byte[] signatureToVerify, TlsConstants.SignatureScheme signatureScheme, Certificate certificate, byte[] transcriptHash) throws HandshakeFailureAlert {
        // https://tools.ietf.org/html/rfc8446#section-4.4.3
        // "The digital signature is then computed over the concatenation of:
        //   -  A string that consists of octet 32 (0x20) repeated 64 times
        //   -  The context string
        //   -  A single 0 byte which serves as the separator
        //   -  The content to be signed"
        ByteBuffer contentToSign = ByteBuffer.allocate(64 + "TLS 1.3, server CertificateVerify".getBytes(ISO_8859_1).length + 1 + transcriptHash.length);
        for (int i = 0; i < 64; i++) {
            contentToSign.put((byte) 0x20);
        }
        // "The context string for a server signature is
        //   "TLS 1.3, server CertificateVerify". "
        contentToSign.put("TLS 1.3, server CertificateVerify".getBytes(ISO_8859_1));
        contentToSign.put((byte) 0x00);
        // "The content that is covered
        //   under the signature is the hash output as described in Section 4.4.1,
        //   namely:
        //      Transcript-Hash(Handshake Context, Certificate)"
        contentToSign.put(transcriptHash);

        boolean verified = false;
        try {
            Signature signatureAlgorithm = getSignatureAlgorithm(signatureScheme);
            signatureAlgorithm.initVerify(certificate);
            signatureAlgorithm.update(contentToSign.array());
            verified = signatureAlgorithm.verify(signatureToVerify);
        } catch (InvalidKeyException e) {
            Logger.debug("Certificate verify: invalid key.");
        } catch (SignatureException e) {
            Logger.debug("Certificate verify: invalid signature.");
        }
        return verified;
    }

    protected void checkCertificateValidity(List<X509Certificate> certificates) throws BadCertificateAlert {
        try {
            if (customTrustManager != null) {
                customTrustManager.checkServerTrusted(certificates.toArray(new X509Certificate[certificates.size()]), "RSA");
            }
            else {
                // https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html#trustmanagerfactory-algorithms
                // "...that validate certificate chains according to the rules defined by the IETF PKIX working group in RFC 5280 or its successor"
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
                trustManagerFactory.init((KeyStore) null);
                X509TrustManager trustMgr = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
                trustMgr.checkServerTrusted(certificates.toArray(new X509Certificate[certificates.size()]), "UNKNOWN");
                // If it gets here, the certificates are ok.
            }
        } catch (NoSuchAlgorithmException e) {
            // Impossible, as we're using the trust managers default algorithm
            throw new RuntimeException("unsupported trust manager algorithm");
        } catch (KeyStoreException e) {
            // Impossible, as we're using the default (JVM) keystore
            throw new RuntimeException("keystore exception");
        } catch (CertificateException e) {
            throw new BadCertificateAlert(extractReason(e).orElse("certificate validation failed"));
        }
    }

    private void sendClientAuth() throws IOException, ErrorAlert {
        CertificateWithPrivateKey certificateWithKey = clientCertificateSelector.apply(clientCertificateAuthorities);

        // Send certificate message (with possible null value for client certificate)
        CertificateMessage certificateMessage =
                new CertificateMessage(certificateWithKey != null? certificateWithKey.getCertificate(): null);
        sender.send(certificateMessage);
        transcriptHash.recordClient(certificateMessage);

        // When certificate is sent, also send a certificate verify message
        if (certificateWithKey != null) {
            TlsConstants.SignatureScheme selectedSignatureScheme = serverSupportedSignatureSchemes.stream()
                    .filter(supportedSignatures::contains)
                    .filter(scheme -> certificateSupportsSignature(certificateWithKey.getCertificate(), scheme))
                    .findFirst()
                    .orElseThrow(() -> new HandshakeFailureAlert("failed to negotiate signature scheme"));

            PrivateKey privateKey = certificateWithKey.getPrivateKey();
            byte[] hash = transcriptHash.getClientHash(TlsConstants.HandshakeType.certificate);
            byte[] signature = computeSignature(hash, privateKey, selectedSignatureScheme, true);
            CertificateVerifyMessage certificateVerify = new CertificateVerifyMessage(selectedSignatureScheme, signature);
            sender.send(certificateVerify);
            transcriptHash.recordClient(certificateVerify);
        }
    }

    private boolean certificateSupportsSignature(X509Certificate cert, TlsConstants.SignatureScheme signatureScheme) {
        String certSignAlg = cert.getSigAlgName();
        if (certSignAlg.toLowerCase().contains("withrsa")) {
            return List.of(rsa_pss_rsae_sha256, rsa_pss_rsae_sha384).contains(signatureScheme);
        }
        else if (certSignAlg.toLowerCase().contains("withecdsa")) {
            return List.of(ecdsa_secp256r1_sha256).contains(signatureScheme);
        }
        else {
            return false;
        }
    }

    private Optional<String> extractReason(CertificateException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof CertPathValidatorException) {
            return Optional.of(cause.getMessage() + ": " + ((CertPathValidatorException) cause).getReason());
        }
        else if (cause instanceof CertPathBuilderException) {
            return Optional.of(cause.getMessage());
        }
        else {
            return Optional.empty();
        }
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public void setCompatibilityMode(boolean compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

    public void addSupportedCiphers(List<TlsConstants.CipherSuite> supportedCiphers) {
        this.supportedCiphers.addAll(supportedCiphers);
    }

    public void addExtensions(List<Extension> extensions) {
        this.requestedExtensions.addAll(extensions);
    }

    public void add(Extension extension) {
        requestedExtensions.add(extension);
    }

    public void setTrustManager(X509TrustManager customTrustManager) {
        this.customTrustManager = customTrustManager;
    }

    /**
     * Add ticket to use for a new session.
     * @param newSessionTicket
     */
    public void setNewSessionTicket(NewSessionTicket newSessionTicket) {
        this.newSessionTicket = newSessionTicket;
    }

    @Override
    public TlsConstants.CipherSuite getSelectedCipher() {
        if (selectedCipher != null) {
            return selectedCipher;
        }
        else {
            throw new IllegalStateException("No (valid) server hello received yet");
        }
    }

    /**
     * Returns tickets provided by the current connection.
     * @return
     */
    public List<NewSessionTicket> getNewSessionTickets() {
        return obtainedNewSessionTickets;
    }

    public List<X509Certificate> getServerCertificateChain() {
        return serverCertificateChain;
    }

    public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
        if (hostnameVerifier != null) {
            this.hostnameVerifier = hostnameVerifier;
        }
    }

    public boolean handshakeFinished() {
        return status == Status.Finished;
    }

    public void setClientCertificateCallback(Function<List<X500Principal>, CertificateWithPrivateKey> callback) {
        clientCertificateSelector = callback;
    }
}
