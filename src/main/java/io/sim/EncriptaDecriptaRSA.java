package io.sim;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.logging.Logger;

public class EncriptaDecriptaRSA {
    private static final Logger logger = Logger.getLogger(EncriptaDecriptaRSA.class.getName());
    private PublicKey publicKey;
    private PrivateKey privateKey;

    // Construtor para gerar um novo par de chaves
    public EncriptaDecriptaRSA() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048); // Tamanho da chave, 2048 é comum e seguro
        KeyPair pair = keyGen.generateKeyPair();
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        logger.info("Par de chaves RSA gerado.");
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    // Método para criptografar dados com uma chave pública fornecida (ex: chave pública do servidor)
    public static byte[] criptografarComPublicKey(byte[] dados, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return cipher.doFinal(dados);
    }

    // Método para descriptografar dados com a chave privada desta instância
    public byte[] descriptografarComPrivateKey(byte[] dadosCifrados) throws Exception {
        if (privateKey == null) {
            throw new IllegalStateException("Chave privada não inicializada para descriptografia.");
        }
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(dadosCifrados);
    }

    // Método utilitário para reconstruir uma chave pública a partir de sua representação em bytes
    public static PublicKey getPublicKeyFromBytes(byte[] keyBytes) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

    // Método utilitário para reconstruir uma chave pública a partir de uma string Base64
    public static PublicKey getPublicKeyFromBase64(String base64PublicKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
        return getPublicKeyFromBytes(keyBytes);
    }
}