package io.sim;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.logging.Logger;
/**
 * Classe utilitária para criptografia e descriptografia usando o algoritmo DES (Data Encryption Standard).
 * Fornece métodos para gerar chaves, criptografar e descriptografar dados.
 */
public class EncriptaDecriptaDES {
    private static final Logger logger = Logger.getLogger(EncriptaDecriptaDES.class.getName());

    private KeyGenerator keyGenerator;
    private SecretKey chaveDES;
    private Cipher cifraDES;
    
    /**
     * Construtor padrão que inicializa o gerador de chaves, a chave secreta e a cifra DES.
     * 
     * @throws Exception Se ocorrer um erro durante a inicialização
     */
    public EncriptaDecriptaDES() throws Exception{
        try {
            // Inicializa o gerador de chaves para o algoritmo DES
            this.keyGenerator = KeyGenerator.getInstance("DES");
            
            // Gera uma chave secreta DES
            this.chaveDES = keyGenerator.generateKey();
            
            // Inicializa a cifra para o algoritmo DES
            this.cifraDES = Cipher.getInstance("DES/ECB/PKCS5Padding");
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("Erro ao inicializar o gerador de chaves DES: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new Exception("Erro ao inicializar a criptografia DES: " + e.getMessage(), e);
        }
    }
    
    /**
     * Construtor que permite especificar uma chave DES personalizada.
     * 
     * @param chavePersonalizada Chave DES personalizada em formato de array de bytes
     * @throws Exception Se ocorrer um erro durante a inicialização
     */
    public EncriptaDecriptaDES(byte[] chavePersonalizada) throws Exception {
        try {
            // Cria uma chave secreta a partir dos bytes fornecidos
            this.chaveDES = new SecretKeySpec(chavePersonalizada, "DES");
            
            // Inicializa a cifra para o algoritmo DES
            this.cifraDES = Cipher.getInstance("DES/ECB/PKCS5Padding");
        } catch (Exception e) {
            throw new Exception("Erro ao inicializar a criptografia DES com chave personalizada: " + e.getMessage(), e);
        }
    }
    
    /**
     * Criptografa uma string usando a chave DES.
     * 
     * @param textoPlano Texto a ser criptografado
     * @return String criptografada em formato Base64
     * @throws Exception Se ocorrer um erro durante a criptografia
     */
    public String criptografar(String textoPlano) throws Exception {
        try {
            // Configura a cifra para o modo de criptografia
            cifraDES.init(Cipher.ENCRYPT_MODE, chaveDES);
            
            // Converte a string para bytes e criptografa
            byte[] textoCifrado = cifraDES.doFinal(textoPlano.getBytes(StandardCharsets.UTF_8));
            //logger.info("Texto criptografado: " + textoCifrado);
            //logger.info("Texto criptografado: " + Base64.getEncoder().encodeToString(textoCifrado));
            // Converte os bytes criptografados para Base64 para facilitar o armazenamento/transmissão
            return Base64.getEncoder().encodeToString(textoCifrado);
        } catch (Exception e) {
            throw new Exception("Erro ao criptografar o texto: " + e.getMessage(), e);
        }
    }
    
    /**
     * Descriptografa uma string criptografada usando a chave DES.
     * 
     * @param textoCifrado Texto criptografado em formato Base64
     * @return String descriptografada
     * @throws Exception Se ocorrer um erro durante a descriptografia
     */
    public String descriptografar(String textoCifrado) throws Exception {
        try {
            //logger.info("Texto cifrado recebido para descriptografar: " + textoCifrado);

            // Configura a cifra para o modo de descriptografia
            cifraDES.init(Cipher.DECRYPT_MODE, chaveDES);
            
            // Decodifica o Base64 e descriptografa
            byte[] bytesDecifrados = cifraDES.doFinal(Base64.getDecoder().decode(textoCifrado));
            
            // Converte os bytes descriptografados para string
            return new String(bytesDecifrados, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new Exception("Erro ao descriptografar o texto: " + e.getMessage(), e);
        }
    }
    
    /**
     * Criptografa um array de bytes usando a chave DES.
     * 
     * @param dados Bytes a serem criptografados
     * @return Bytes criptografados
     * @throws Exception Se ocorrer um erro durante a criptografia
     */
    public byte[] criptografar(byte[] dados) throws Exception {
        try {
            // Configura a cifra para o modo de criptografia
            cifraDES.init(Cipher.ENCRYPT_MODE, chaveDES);
            
            // Criptografa os bytes
            return cifraDES.doFinal(dados);
        } catch (Exception e) {
            throw new Exception("Erro ao criptografar os dados: " + e.getMessage(), e);
        }
    }
    
    /**
     * Descriptografa um array de bytes criptografados usando a chave DES.
     * 
     * @param dadosCifrados Bytes criptografados
     * @return Bytes descriptografados
     * @throws Exception Se ocorrer um erro durante a descriptografia
     */
    public byte[] descriptografar(byte[] dadosCifrados) throws Exception {
        try {
            // Configura a cifra para o modo de descriptografia
            cifraDES.init(Cipher.DECRYPT_MODE, chaveDES);
            
            // Descriptografa os bytes
            return cifraDES.doFinal(dadosCifrados);
        } catch (Exception e) {
            throw new Exception("Erro ao descriptografar os dados: " + e.getMessage(), e);
        }
    }
    
    /**
     * Obtém a chave secreta DES atual.
     * 
     * @return Chave secreta DES
     */
    public SecretKey getChaveDES() {
        return chaveDES;
    }
    
    /**
     * Obtém os bytes da chave secreta DES atual.
     * 
     * @return Bytes da chave secreta DES
     */
    public byte[] getChaveDESBytes() {
        return chaveDES.getEncoded();
    }
    
    /**
     * Obtém a chave secreta DES em formato Base64.
     * 
     * @return Chave secreta DES em formato Base64
     */
    public String getChaveDESBase64() {
        return Base64.getEncoder().encodeToString(chaveDES.getEncoded());
    }
    
    /**
     * Define uma nova chave secreta DES a partir de bytes.
     * 
     * @param chaveBytes Bytes da nova chave secreta
     */
    public void setChaveDES(byte[] chaveBytes) {
        this.chaveDES = new SecretKeySpec(chaveBytes, "DES");
    }
    
    /**
     * Define uma nova chave secreta DES a partir de uma string Base64.
     * 
     * @param chaveBase64 String Base64 da nova chave secreta
     * @throws Exception Se ocorrer um erro ao decodificar a chave
     */
    public void setChaveDESFromBase64(String chaveBase64) throws Exception {
        try {
            byte[] chaveBytes = Base64.getDecoder().decode(chaveBase64);
            this.chaveDES = new SecretKeySpec(chaveBytes, "DES");
        } catch (Exception e) {
            throw new Exception("Erro ao definir a chave DES a partir da string Base64: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gera uma nova chave secreta DES.
     * 
     * @throws Exception Se ocorrer um erro ao gerar a chave
     */
    public void gerarNovaChave() throws Exception {
        try {
            this.chaveDES = keyGenerator.generateKey();
        } catch (Exception e) {
            throw new Exception("Erro ao gerar nova chave DES: " + e.getMessage(), e);
        }
    }
}
