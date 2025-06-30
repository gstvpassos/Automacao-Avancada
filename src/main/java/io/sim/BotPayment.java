package io.sim;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import io.sim.utils.JsonUtil;

/**
 * Classe responsável por automatizar pagamentos entre contas.
 * Funciona como um bot que realiza transferências periódicas ou sob demanda.
 */
public class BotPayment extends Thread {
    
    private static final Logger logger = Logger.getLogger(BotPayment.class.getName());
    
    // Identificador único do bot
    private String botId;
    
    // Conta de origem para os pagamentos
    private Account sourceAccount;
    
    // Conexão com o servidor AlphaBank
    private Socket bankConnection;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    
    // Controle de execução
    private AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    
    // Configurações
    private long paymentInterval; // Intervalo entre pagamentos automáticos em milissegundos
    private boolean autoPaymentEnabled; // Indica se o pagamento automático está habilitado
    
    // Histórico de pagamentos
    private List<PaymentRecord> paymentHistory;
    
    // Utilitário de criptografia
    private EncriptaDecriptaDES sessionEncryptor;
    
    // Host e porta do banco
    private String bankHost;
    private int bankPort;
    
    /**
     * Classe interna para representar um registro de pagamento
     */
    private static class PaymentRecord {
        private String paymentId;
        private String destination;
        private double amount;
        private String description;
        private long timestamp;
        private boolean successful;
        
        public PaymentRecord(String paymentId, String destination, double amount, String description, boolean successful) {
            this.paymentId = paymentId;
            this.destination = destination;
            this.amount = amount;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
            this.successful = successful;
        }
        
        public String toJson() {
            return JsonUtil.toJson(this);
        }
    }
    
    /**
     * Construtor principal do BotPayment.
     * 
     * @param sourceAccount Conta de origem para os pagamentos
     * @param bankHost Host do servidor AlphaBank
     * @param bankPort Porta do servidor AlphaBank
     * @param paymentInterval Intervalo entre pagamentos automáticos (em milissegundos)
     * @param autoPaymentEnabled Indica se o pagamento automático está habilitado
     */
    public BotPayment(Account sourceAccount, String bankHost, int bankPort, 
                     long paymentInterval, boolean autoPaymentEnabled) {
        this.botId = "BOT_" + UUID.randomUUID().toString().substring(0, 8);
        this.sourceAccount = sourceAccount;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.paymentInterval = paymentInterval;
        this.autoPaymentEnabled = autoPaymentEnabled;
        this.paymentHistory = new ArrayList<>();
        
        try {
            // Inicializa o encriptador
            this.sessionEncryptor = new EncriptaDecriptaDES();
            
            // Tenta conectar ao servidor AlphaBank
            try {
                connectToBank(bankHost, bankPort);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Erro ao conectar ao AlphaBank: " + e.getMessage(), e);
                // Não propaga a exceção para permitir que o BotPayment continue funcionando
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao inicializar BotPayment: " + e.getMessage(), e);
        }
        
        logger.info("BotPayment " + botId + " criado com sucesso");
    }
    
    /**
     * Construtor simplificado do BotPayment.
     * 
     * @param sourceAccount Conta de origem para os pagamentos
     * @param bankHost Host do servidor AlphaBank
     * @param bankPort Porta do servidor AlphaBank
     */
    public BotPayment(Account sourceAccount, String bankHost, int bankPort) {
        this(sourceAccount, bankHost, bankPort, 60000, false); // Padrão: 1 minuto, sem pagamento automático
    }
    
    /**
     * Estabelece conexão com o servidor AlphaBank.
     * 
     * @param host Host do servidor
     * @param port Porta do servidor
     * @throws IOException Se ocorrer um erro de conexão
     */
    private void connectToBank(String host, int port) throws IOException {
        try {
            logger.info("[BotPayment " + botId + "] Iniciando conexão com AlphaBank em " + host + ":" + port);
            this.bankConnection = new Socket(host, port);
            
            logger.info("[BotPayment " + botId + "] Socket criado. Criando streams...");
            this.out = new ObjectOutputStream(bankConnection.getOutputStream());
            this.out.flush(); 
            this.in = new ObjectInputStream(bankConnection.getInputStream());
            logger.info("[BotPayment " + botId + "] Streams de entrada e saída criadas.");

            // 1. ENVIAR ID DO CLIENTE (botId) PARA O SERVIDOR
            logger.info("[BotPayment " + botId + "] Enviando ID do cliente: " + this.botId);
            out.writeObject(this.botId);
            out.flush();

            // 2. RECEBER CHAVE PÚBLICA RSA DO SERVIDOR (em Base64)
            String serverRsaPublicKeyBase64 = (String) in.readObject();
            logger.info("[BotPayment " + botId + "] Chave pública RSA do servidor recebida.");
            // Você precisará da classe EncriptaDecriptaRSA e seu método estático aqui
            PublicKey serverRsaPublicKey = EncriptaDecriptaRSA.getPublicKeyFromBase64(serverRsaPublicKeyBase64);

            // 3. GERAR UMA CHAVE DE SESSÃO DES ALEATÓRIA
            EncriptaDecriptaDES desKeyGenerator = new EncriptaDecriptaDES(); 
            byte[] desSessionKeyBytes = desKeyGenerator.getChaveDESBytes();
            
            this.sessionEncryptor = new EncriptaDecriptaDES(desSessionKeyBytes);
            logger.info("[BotPayment " + botId + "] Chave DES de sessão gerada.");

            // 4. CRIPTOGRAFAR A CHAVE DE SESSÃO DES COM A CHAVE PÚBLICA RSA DO SERVIDOR
            byte[] encryptedDesSessionKey = EncriptaDecriptaRSA.criptografarComPublicKey(desSessionKeyBytes, serverRsaPublicKey);

            // 5. ENVIAR A CHAVE DE SESSÃO DES (CRIPTOGRAFADA COM RSA) PARA O SERVIDOR
            out.writeObject(encryptedDesSessionKey);
            out.flush();
            logger.info("[BotPayment " + botId + "] Chave DES de sessão criptografada enviada ao servidor.");

            // 6. AGUARDAR CONFIRMAÇÃO DE CONEXÃO SEGURA DO SERVIDOR (CRIPTOGRAFADA COM A CHAVE DES DE SESSÃO)
            String encryptedConfirmation = (String) in.readObject();
            String confirmationMessage = this.sessionEncryptor.descriptografar(encryptedConfirmation);

            if (!"CONNECTED_SECURELY".equals(confirmationMessage)) {
                throw new IOException("Falha ao estabelecer conexão segura com o servidor AlphaBank. Resposta de confirmação inválida: " + confirmationMessage);
            }
            logger.info("[BotPayment " + botId + "] Conexão segura estabelecida com o servidor AlphaBank.");

            sendAuthenticationRequest(); 
            
        } catch (IOException e) {
            logger.log(Level.SEVERE, "[BotPayment " + botId + "] Erro de IO durante conexão/negociação de chave com AlphaBank: " + e.getMessage(), e);
            throw e; 
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "[BotPayment " + botId + "] Erro de Classe Não Encontrada durante negociação de chave: " + e.getMessage(), e);
            throw new IOException("Erro de comunicação com o servidor (classe não encontrada)", e);
        } catch (Exception e) { 
            logger.log(Level.SEVERE, "[BotPayment " + botId + "] Exceção geral durante conexão/negociação de chave: " + e.getMessage(), e);
            throw new IOException("Erro na configuração da criptografia ou negociação de chave: " + e.getMessage(), e);
        }
    }
    
    /**
     * Envia solicitação de autenticação para o servidor AlphaBank.
     * 
     * @throws IOException Se ocorrer um erro de comunicação
     */
    private void sendAuthenticationRequest() throws IOException {
        try {
            // Cria objeto de autenticação
            JSONObject authJson = new JSONObject();
            authJson.put("action", "AUTHENTICATE");
            authJson.put("login", sourceAccount.getLogin());
            authJson.put("password", sourceAccount.getSenha());
            authJson.put("clientType", "BOT_PAYMENT");
            
            String authRequest = authJson.toString();
            logger.info("Enviando requisição de autenticação: " + authRequest);
            
            // Criptografa a solicitação
            String encryptedRequest = sessionEncryptor.criptografar(authRequest);
            
            // Envia para o servidor
            out.writeObject(encryptedRequest);
            out.flush();
            
            // Aguarda resposta
            String encryptedResponse = (String) in.readObject();
            String response = sessionEncryptor.descriptografar(encryptedResponse);
            logger.info("Resposta de autenticação recebida: " + response);
            
            // Verifica se a autenticação foi bem-sucedida
            Map<String, Object> responseMap = JsonUtil.jsonToMap(response);
            if (responseMap == null || !"success".equals(responseMap.get("status"))) {
                throw new IOException("Falha na autenticação: " + response);
            }
            
            logger.info("Autenticação bem-sucedida");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro na autenticação: " + e.getMessage(), e);
            throw new IOException("Erro na autenticação", e);
        }
    }
    
    /**
     * Método principal da thread.
     * Inicia o agendador de pagamentos automáticos se habilitado.
     */
    @Override
    public void run() {
        this.running.set(true);
        
        logger.info("BotPayment " + botId + " iniciado");
        
        // Inicializa o agendador se o pagamento automático estiver habilitado
        if (autoPaymentEnabled) {
            startAutoPaymentScheduler();
        }
        
        // Loop principal para manter a thread ativa
        while (running.get()) {
            try {
                // Verifica se há mensagens do servidor
                if (bankConnection != null && !bankConnection.isClosed() && 
                    bankConnection.getInputStream().available() > 0) {
                    processServerMessage();
                }
                
                // Pausa para evitar uso excessivo de CPU
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // Interrupção normal, verifica se deve continuar
                logger.info("BotPayment " + botId + " interrompido");
                Thread.currentThread().interrupt();
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro no loop principal: " + e.getMessage(), e);
                
                // Tenta reconectar em caso de erro de conexão
                if (e instanceof IOException) {
                    try {
                        reconnect();
                    } catch (Exception reconnectError) {
                        logger.log(Level.SEVERE, "Falha ao reconectar: " + reconnectError.getMessage(), reconnectError);
                    }
                }
                
                try {
                    // Pausa para evitar spam de erros
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }
        
        // Limpa recursos ao encerrar
        cleanup();
        
        logger.info("BotPayment " + botId + " encerrado");
    }
    
    /**
     * Inicia o agendador de pagamentos automáticos.
     */
    private void startAutoPaymentScheduler() {
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        // Agenda a tarefa de pagamento automático
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Aqui seria implementada a lógica para determinar pagamentos automáticos
                logger.info("Verificando pagamentos automáticos...");
                
                // Exemplo: poderia buscar pagamentos pendentes de um serviço
                // List<PaymentRequest> pendingPayments = fetchPendingPayments();
                // for (PaymentRequest payment : pendingPayments) {
                //     processPayment(payment.getDestination(), payment.getAmount(), payment.getDescription());
                // }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro no pagamento automático: " + e.getMessage(), e);
            }
        }, 0, paymentInterval, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Processa mensagens recebidas do servidor AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro no processamento
     */
    private void processServerMessage() throws Exception {
        try {
            // Lê a mensagem criptografada
            String encryptedMessage = (String) in.readObject();
            
            // Descriptografa a mensagem
            String message = sessionEncryptor.descriptografar(encryptedMessage);
            
            logger.info("Mensagem recebida: " + message);
            
            // Processa a mensagem usando JsonUtil
            Map<String, Object> messageMap = JsonUtil.jsonToMap(message);
            if (messageMap != null) {
                String messageType = (String) messageMap.get("type");
                
                if ("PAYMENT_CONFIRMATION".equals(messageType)) {
                    // Processa confirmação de pagamento
                    String paymentId = (String) messageMap.get("paymentId");
                    boolean success = (boolean) messageMap.get("success");
                    
                    logger.info("Confirmação de pagamento recebida: ID=" + paymentId + ", Sucesso=" + success);
                    
                    // Atualiza o histórico de pagamentos
                    updatePaymentHistory(paymentId, success);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao processar mensagem do servidor: " + e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Atualiza o histórico de pagamentos com o resultado de um pagamento.
     * 
     * @param paymentId ID do pagamento
     * @param success Indica se o pagamento foi bem-sucedido
     */
    private void updatePaymentHistory(String paymentId, boolean success) {
        for (PaymentRecord record : paymentHistory) {
            if (record.paymentId.equals(paymentId)) {
                record.successful = success;
                logger.info("Histórico de pagamento atualizado: " + record.toJson());
                break;
            }
        }
    }
    
    /**
     * Tenta reconectar ao servidor AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro na reconexão
     */
    private void reconnect() throws Exception {
        logger.info("Tentando reconectar ao AlphaBank...");
        
        // Fecha a conexão atual
        if (bankConnection != null) {
            try {
                bankConnection.close();
            } catch (IOException e) {
                // Ignora erros ao fechar
            }
        }
        
        // Tenta reconectar
        connectToBank(bankHost, bankPort);
        
        logger.info("Reconexão bem-sucedida");
    }
    
    /**
     * Processa um pagamento para uma conta de destino.
     * 
     * @param destination Conta de destino
     * @param amount Valor a ser transferido
     * @param description Descrição do pagamento
     * @return ID do pagamento ou null se falhar
     */
    public String processPayment(String destination, double amount, String description) {
        if (amount <= 0) {
            logger.warning("Valor de pagamento inválido: " + amount);
            return null;
        }
        
        String paymentId = UUID.randomUUID().toString();
        
        try {
            logger.info("Processando pagamento: Destino=" + destination + ", Valor=" + amount + 
                       ", Descrição=" + description);
            
            // Verifica se está conectado ao banco
            if (bankConnection == null || bankConnection.isClosed()) {
                try {
                    connectToBank(bankHost, bankPort);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Falha ao conectar ao banco para pagamento: " + e.getMessage(), e);
                    
                    // Registra o pagamento como falha
                    PaymentRecord record = new PaymentRecord(paymentId, destination, amount, description, false);
                    paymentHistory.add(record);
                    
                    return null;
                }
            }
            
            // Cria objeto de pagamento
            JSONObject paymentJson = new JSONObject();
            paymentJson.put("action", "PAYMENT");
            paymentJson.put("paymentId", paymentId);
            paymentJson.put("sourceAccount", sourceAccount.getLogin());
            paymentJson.put("destinationAccount", destination);
            paymentJson.put("amount", amount);
            paymentJson.put("description", description);
            
            String paymentRequest = paymentJson.toString();
            logger.info("Enviando requisição de pagamento: " + paymentRequest);
            
            // Criptografa a solicitação
            String encryptedRequest = sessionEncryptor.criptografar(paymentRequest);
            
            // Envia para o servidor
            out.writeObject(encryptedRequest);
            out.flush();
            
            // Aguarda resposta
            String encryptedResponse = (String) in.readObject();
            String response = sessionEncryptor.descriptografar(encryptedResponse);
            logger.info("Resposta de pagamento recebida: " + response);
            
            // Verifica se o pagamento foi bem-sucedido
            Map<String, Object> responseMap = JsonUtil.jsonToMap(response);
            boolean success = responseMap != null && "success".equals(responseMap.get("status"));
            
            // Registra o pagamento no histórico
            PaymentRecord record = new PaymentRecord(paymentId, destination, amount, description, success);
            paymentHistory.add(record);
            
            if (success) {
                logger.info("Pagamento processado com sucesso: " + paymentId);
                return paymentId;
            } else {
                logger.warning("Falha no processamento do pagamento: " + 
                              (responseMap != null ? responseMap.get("message") : "Erro desconhecido"));
                return null;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao processar pagamento: " + e.getMessage(), e);
            
            // Registra o pagamento como falha
            PaymentRecord record = new PaymentRecord(paymentId, destination, amount, description, false);
            paymentHistory.add(record);
            
            return null;
        }
    }
    
    /**
     * Para o bot de pagamento.
     */
    public void stopBot() {
        this.running.set(false);
        
        // Para o agendador se estiver ativo
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!scheduler.isTerminated()) {
                    scheduler.shutdownNow();
                }
            }
        }
        
        // Interrompe a thread
        this.interrupt();
    }
    
    /**
     * Limpa recursos ao encerrar.
     */
    private void cleanup() {
        try {
            // Fecha a conexão com o banco
            if (bankConnection != null) {
                bankConnection.close();
            }
            
            // Para o agendador se estiver ativo
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
            
            logger.info("Recursos liberados");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao liberar recursos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Obtém o histórico de pagamentos.
     * 
     * @return Lista de registros de pagamento
     */
    public List<PaymentRecord> getPaymentHistory() {
        return new ArrayList<>(paymentHistory);
    }
    
    /**
     * Obtém o ID do bot.
     * 
     * @return ID do bot
     */
    public String getBotId() {
        return botId;
    }
    
    /**
     * Obtém a conta de origem.
     * 
     * @return Conta de origem
     */
    public Account getSourceAccount() {
        return sourceAccount;
    }
}
