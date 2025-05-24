package io.sim;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Classe que representa uma conta bancária no sistema AlphaBank.
 * Gerencia saldo, transações e informações do titular.
 */
public class Account implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(Account.class.getName());
    
    // Informações da conta
    private final String senha;
    private final String login;
    private double balance;
    
    // Histórico de transações
    private final List<Transaction> transactions;
    
    // Status da conta
    private boolean active;
    private final Date creationDate;
    private Date lastAccessDate;
    
    /**
     * Construtor da classe Account.
     * 
     * @param senha ID da conta
     * @param login Nome do titular da conta
     * @param initialBalance Saldo inicial da conta
     */
    public Account(String senha, String login, double initialBalance) {
        this.senha = senha;
        this.login = login;
        this.balance = initialBalance;
        this.transactions = new ArrayList<>();
        this.active = true;
        this.creationDate = new Date();
        this.lastAccessDate = new Date();
        
        logger.info("Conta criada: " + senha + " - Titular: " + login + " - Saldo inicial: " + initialBalance);
    }
    
    /**
     * Construtor alternativo que gera um ID de conta automaticamente.
     * 
     * @param login Nome do titular da conta
     * @param initialBalance Saldo inicial da conta
     */
    public Account(String login, double initialBalance) {
        this(UUID.randomUUID().toString(), login, initialBalance);
    }
    
    /**
     * Realiza um depósito na conta.
     * 
     * @param amount Valor a ser depositado
     * @param description Descrição da transação
     * @return true se o depósito foi realizado com sucesso, false caso contrário
     */
    public synchronized boolean deposit(double amount, String description) {
        if (!active) {
            logger.warning("Tentativa de depósito em conta inativa: " + senha);
            return false;
        }
        
        if (amount <= 0) {
            logger.warning("Tentativa de depósito com valor inválido: " + amount);
            return false;
        }
        
        balance += amount;
        lastAccessDate = new Date();
        
        // Registra a transação
        Transaction transaction = new Transaction(
                UUID.randomUUID().toString(),
                Transaction.TransactionType.DEPOSIT,
                amount,
                description,
                new Date()
        );
        transactions.add(transaction);
        
        logger.info("Depósito realizado: " + amount + " - Conta: " + senha + " - Novo saldo: " + balance);
        return true;
    }
    
    /**
     * Realiza um saque na conta.
     * 
     * @param amount Valor a ser sacado
     * @param description Descrição da transação
     * @return true se o saque foi realizado com sucesso, false caso contrário
     */
    public synchronized boolean withdraw(double amount, String description) {
        if (!active) {
            logger.warning("Tentativa de saque em conta inativa: " + senha);
            return false;
        }
        
        if (amount <= 0) {
            logger.warning("Tentativa de saque com valor inválido: " + amount);
            return false;
        }
        
        if (balance < amount) {
            logger.warning("Saldo insuficiente para saque: " + amount + " - Saldo atual: " + balance);
            return false;
        }
        
        balance -= amount;
        lastAccessDate = new Date();
        
        // Registra a transação
        Transaction transaction = new Transaction(
                UUID.randomUUID().toString(),
                Transaction.TransactionType.WITHDRAWAL,
                amount,
                description,
                new Date()
        );
        transactions.add(transaction);
        
        logger.info("Saque realizado: " + amount + " - Conta: " + senha + " - Novo saldo: " + balance);
        return true;
    }
    
    /**
     * Realiza uma transferência para outra conta.
     * 
     * @param destinationAccount Conta de destino
     * @param amount Valor a ser transferido
     * @param description Descrição da transação
     * @return true se a transferência foi realizada com sucesso, false caso contrário
     */
    public synchronized boolean transfer(Account destinationAccount, double amount, String description) {
        if (!active) {
            logger.warning("Tentativa de transferência de conta inativa: " + senha);
            return false;
        }
        
        if (destinationAccount == null) {
            logger.warning("Conta de destino inválida");
            return false;
        }
        
        if (!destinationAccount.isActive()) {
            logger.warning("Conta de destino inativa: " + destinationAccount.getSenha());
            return false;
        }
        
        if (amount <= 0) {
            logger.warning("Tentativa de transferência com valor inválido: " + amount);
            return false;
        }
        
        if (balance < amount) {
            logger.warning("Saldo insuficiente para transferência: " + amount + " - Saldo atual: " + balance);
            return false;
        }
        
        // Realiza o saque na conta de origem
        if (!withdraw(amount, "Transferência para " + destinationAccount.getSenha() + ": " + description)) {
            return false;
        }
        
        // Realiza o depósito na conta de destino
        if (!destinationAccount.deposit(amount, "Transferência de " + senha + ": " + description)) {
            // Se o depósito falhar, desfaz o saque
            deposit(amount, "Estorno de transferência para " + destinationAccount.getSenha());
            return false;
        }
        
        logger.info("Transferência realizada: " + amount + " - De: " + senha + " - Para: " + destinationAccount.getSenha());
        return true;
    }
    
    /**
     * Ativa a conta.
     * 
     * @return true se a conta foi ativada com sucesso, false caso contrário
     */
    public synchronized boolean activate() {
        if (active) {
            logger.info("Conta já está ativa: " + senha);
            return true;
        }
        
        active = true;
        lastAccessDate = new Date();
        logger.info("Conta ativada: " + senha);
        return true;
    }
    
    /**
     * Desativa a conta.
     * 
     * @return true se a conta foi desativada com sucesso, false caso contrário
     */
    public synchronized boolean deactivate() {
        if (!active) {
            logger.info("Conta já está inativa: " + senha);
            return true;
        }
        
        active = false;
        lastAccessDate = new Date();
        logger.info("Conta desativada: " + senha);
        return true;
    }
    
    /**
     * Obtém o histórico de transações da conta.
     * 
     * @return Lista de transações
     */
    public synchronized List<Transaction> getTransactionHistory() {
        lastAccessDate = new Date();
        return new ArrayList<>(transactions);
    }
    
    /**
     * Obtém o extrato da conta.
     * 
     * @return String contendo o extrato da conta
     */
    public synchronized String getStatement() {
        lastAccessDate = new Date();
        
        StringBuilder statement = new StringBuilder();
        statement.append("Extrato da Conta: ").append(senha).append("\n");
        statement.append("Titular: ").append(login).append("\n");
        statement.append("Saldo Atual: R$ ").append(String.format("%.2f", balance)).append("\n");
        statement.append("Data de Criação: ").append(creationDate).append("\n");
        statement.append("Último Acesso: ").append(lastAccessDate).append("\n");
        statement.append("Status: ").append(active ? "Ativa" : "Inativa").append("\n\n");
        statement.append("Histórico de Transações:\n");
        
        if (transactions.isEmpty()) {
            statement.append("Nenhuma transação encontrada.\n");
        } else {
            for (Transaction transaction : transactions) {
                statement.append(transaction.toString()).append("\n");
            }
        }
        
        return statement.toString();
    }
    
    /**
     * Classe interna que representa uma transação bancária.
     */
    public static class Transaction implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * Enum que representa os tipos de transação.
         */
        public enum TransactionType {
            DEPOSIT("Depósito"),
            WITHDRAWAL("Saque"),
            TRANSFER("Transferência");
            
            private final String description;
            
            TransactionType(String description) {
                this.description = description;
            }
            
            public String getDescription() {
                return description;
            }
        }
        
        private final String transactionId;
        private final TransactionType type;
        private final double amount;
        private final String description;
        private final Date timestamp;
        
        /**
         * Construtor da classe Transaction.
         * 
         * @param transactionId ID da transação
         * @param type Tipo da transação
         * @param amount Valor da transação
         * @param description Descrição da transação
         * @param timestamp Data e hora da transação
         */
        public Transaction(String transactionId, TransactionType type, double amount, String description, Date timestamp) {
            this.transactionId = transactionId;
            this.type = type;
            this.amount = amount;
            this.description = description;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s: R$ %.2f - %s", 
                    timestamp, 
                    type.getDescription(), 
                    amount, 
                    description);
        }
        
        // Getters
        
        public String getTransactionId() {
            return transactionId;
        }
        
        public TransactionType getType() {
            return type;
        }
        
        public double getAmount() {
            return amount;
        }
        
        public String getDescription() {
            return description;
        }
        
        public Date getTimestamp() {
            return timestamp;
        }
    }
    
    // Getters e Setters
    
    public String getSenha() {
        return senha;
    }
    
    public String getLogin() {
        return login;
    }
    
    public synchronized double getBalance() {
        lastAccessDate = new Date();
        return balance;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public Date getCreationDate() {
        return creationDate;
    }
    
    public Date getLastAccessDate() {
        return lastAccessDate;
    }
}
