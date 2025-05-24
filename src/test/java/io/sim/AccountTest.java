package io.sim;

import junit.framework.TestCase;
import java.util.List;

/**
 * Testes unitários para a classe Account.
 * Utiliza JUnit TestCase para validar o funcionamento da Account.
 */
public class AccountTest extends TestCase {
    
    private Account account;
    private Account destinationAccount;
    
    /**
     * Configuração inicial para os testes.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        // Cria uma conta para os testes
        account = new Account("ACC001", "Teste da Silva", 1000.0);
        
        // Cria uma conta de destino para testes de transferência
        destinationAccount = new Account("ACC002", "João Recebedor", 500.0);
    }
    
    /**
     * Testa a criação da conta.
     */
    public void testAccountCreation() {
        assertNotNull("Conta não deve ser nula", account);
        assertEquals("ID da conta deve ser ACC001", "ACC001", account.getSenha());
        assertEquals("Nome do titular deve ser Teste da Silva", "Teste da Silva", account.getLogin());
        assertEquals("Saldo inicial deve ser 1000.0", 1000.0, account.getBalance());
        assertTrue("Conta deve estar ativa", account.isActive());
        assertNotNull("Data de criação não deve ser nula", account.getCreationDate());
        assertNotNull("Data de último acesso não deve ser nula", account.getLastAccessDate());
    }
    
    /**
     * Testa a criação da conta com ID gerado automaticamente.
     */
    public void testAccountCreationWithAutoId() {
        Account autoIdAccount = new Account("Auto Gerado", 2000.0);
        
        assertNotNull("Conta não deve ser nula", autoIdAccount);
        assertNotNull("ID da conta não deve ser nulo", autoIdAccount.getSenha());
        assertTrue("ID da conta deve ter comprimento maior que 0", autoIdAccount.getSenha().length() > 0);
        assertEquals("Nome do titular deve ser Auto Gerado", "Auto Gerado", autoIdAccount.getLogin());
        assertEquals("Saldo inicial deve ser 2000.0", 2000.0, autoIdAccount.getBalance());
    }
    
    /**
     * Testa o depósito na conta.
     */
    public void testDeposit() {
        boolean result = account.deposit(500.0, "Depósito de teste");
        
        assertTrue("Depósito deve ser bem-sucedido", result);
        assertEquals("Saldo após depósito deve ser 1500.0", 1500.0, account.getBalance());
        
        // Testa depósito com valor negativo
        result = account.deposit(-100.0, "Depósito inválido");
        
        assertFalse("Depósito com valor negativo deve falhar", result);
        assertEquals("Saldo não deve mudar após depósito inválido", 1500.0, account.getBalance());
        
        // Testa depósito com valor zero
        result = account.deposit(0.0, "Depósito inválido");
        
        assertFalse("Depósito com valor zero deve falhar", result);
        assertEquals("Saldo não deve mudar após depósito inválido", 1500.0, account.getBalance());
    }
    
    /**
     * Testa o saque na conta.
     */
    public void testWithdraw() {
        boolean result = account.withdraw(300.0, "Saque de teste");
        
        assertTrue("Saque deve ser bem-sucedido", result);
        assertEquals("Saldo após saque deve ser 700.0", 700.0, account.getBalance());
        
        // Testa saque com valor maior que o saldo
        result = account.withdraw(1000.0, "Saque inválido");
        
        assertFalse("Saque com valor maior que o saldo deve falhar", result);
        assertEquals("Saldo não deve mudar após saque inválido", 700.0, account.getBalance());
        
        // Testa saque com valor negativo
        result = account.withdraw(-100.0, "Saque inválido");
        
        assertFalse("Saque com valor negativo deve falhar", result);
        assertEquals("Saldo não deve mudar após saque inválido", 700.0, account.getBalance());
        
        // Testa saque com valor zero
        result = account.withdraw(0.0, "Saque inválido");
        
        assertFalse("Saque com valor zero deve falhar", result);
        assertEquals("Saldo não deve mudar após saque inválido", 700.0, account.getBalance());
    }
    
    /**
     * Testa a transferência entre contas.
     */
    public void testTransfer() {
        boolean result = account.transfer(destinationAccount, 300.0, "Transferência de teste");
        
        assertTrue("Transferência deve ser bem-sucedida", result);
        assertEquals("Saldo da conta de origem após transferência deve ser 700.0", 700.0, account.getBalance());
        assertEquals("Saldo da conta de destino após transferência deve ser 800.0", 800.0, destinationAccount.getBalance());
        
        // Testa transferência com valor maior que o saldo
        result = account.transfer(destinationAccount, 1000.0, "Transferência inválida");
        
        assertFalse("Transferência com valor maior que o saldo deve falhar", result);
        assertEquals("Saldo da conta de origem não deve mudar após transferência inválida", 700.0, account.getBalance());
        assertEquals("Saldo da conta de destino não deve mudar após transferência inválida", 800.0, destinationAccount.getBalance());
        
        // Testa transferência com valor negativo
        result = account.transfer(destinationAccount, -100.0, "Transferência inválida");
        
        assertFalse("Transferência com valor negativo deve falhar", result);
        assertEquals("Saldo da conta de origem não deve mudar após transferência inválida", 700.0, account.getBalance());
        assertEquals("Saldo da conta de destino não deve mudar após transferência inválida", 800.0, destinationAccount.getBalance());
        
        // Testa transferência com valor zero
        result = account.transfer(destinationAccount, 0.0, "Transferência inválida");
        
        assertFalse("Transferência com valor zero deve falhar", result);
        assertEquals("Saldo da conta de origem não deve mudar após transferência inválida", 700.0, account.getBalance());
        assertEquals("Saldo da conta de destino não deve mudar após transferência inválida", 800.0, destinationAccount.getBalance());
        
        // Testa transferência para conta nula
        result = account.transfer(null, 100.0, "Transferência inválida");
        
        assertFalse("Transferência para conta nula deve falhar", result);
        assertEquals("Saldo da conta de origem não deve mudar após transferência inválida", 700.0, account.getBalance());
    }
    
    /**
     * Testa a ativação e desativação da conta.
     */
    public void testActivateDeactivate() {
        // Inicialmente a conta está ativa
        assertTrue("Conta deve estar ativa inicialmente", account.isActive());
        
        // Testa desativação
        boolean result = account.deactivate();
        
        assertTrue("Desativação deve ser bem-sucedida", result);
        assertFalse("Conta deve estar inativa após desativação", account.isActive());
        
        // Testa operações em conta inativa
        result = account.deposit(100.0, "Depósito em conta inativa");
        
        assertFalse("Depósito em conta inativa deve falhar", result);
        assertEquals("Saldo não deve mudar após depósito em conta inativa", 1000.0, account.getBalance());
        
        result = account.withdraw(100.0, "Saque em conta inativa");
        
        assertFalse("Saque em conta inativa deve falhar", result);
        assertEquals("Saldo não deve mudar após saque em conta inativa", 1000.0, account.getBalance());
        
        result = account.transfer(destinationAccount, 100.0, "Transferência de conta inativa");
        
        assertFalse("Transferência de conta inativa deve falhar", result);
        assertEquals("Saldo da conta de origem não deve mudar após transferência de conta inativa", 1000.0, account.getBalance());
        assertEquals("Saldo da conta de destino não deve mudar após transferência de conta inativa", 500.0, destinationAccount.getBalance());
        
        // Testa ativação
        result = account.activate();
        
        assertTrue("Ativação deve ser bem-sucedida", result);
        assertTrue("Conta deve estar ativa após ativação", account.isActive());
        
        // Testa operações após reativação
        result = account.deposit(100.0, "Depósito após reativação");
        
        assertTrue("Depósito após reativação deve ser bem-sucedido", result);
        assertEquals("Saldo após depósito deve ser 1100.0", 1100.0, account.getBalance());
    }
    
    /**
     * Testa o histórico de transações.
     */
    public void testTransactionHistory() {
        // Realiza algumas transações
        account.deposit(200.0, "Depósito 1");
        account.withdraw(100.0, "Saque 1");
        account.deposit(300.0, "Depósito 2");
        
        // Obtém o histórico de transações
        List<Account.Transaction> history = account.getTransactionHistory();
        
        assertNotNull("Histórico de transações não deve ser nulo", history);
        assertEquals("Histórico deve conter 3 transações", 3, history.size());
        
        // Verifica a primeira transação
        Account.Transaction transaction1 = history.get(0);
        assertEquals("Tipo da primeira transação deve ser DEPOSIT", Account.Transaction.TransactionType.DEPOSIT, transaction1.getType());
        assertEquals("Valor da primeira transação deve ser 200.0", 200.0, transaction1.getAmount());
        assertEquals("Descrição da primeira transação deve ser 'Depósito 1'", "Depósito 1", transaction1.getDescription());
        
        // Verifica a segunda transação
        Account.Transaction transaction2 = history.get(1);
        assertEquals("Tipo da segunda transação deve ser WITHDRAWAL", Account.Transaction.TransactionType.WITHDRAWAL, transaction2.getType());
        assertEquals("Valor da segunda transação deve ser 100.0", 100.0, transaction2.getAmount());
        assertEquals("Descrição da segunda transação deve ser 'Saque 1'", "Saque 1", transaction2.getDescription());
        
        // Verifica a terceira transação
        Account.Transaction transaction3 = history.get(2);
        assertEquals("Tipo da terceira transação deve ser DEPOSIT", Account.Transaction.TransactionType.DEPOSIT, transaction3.getType());
        assertEquals("Valor da terceira transação deve ser 300.0", 300.0, transaction3.getAmount());
        assertEquals("Descrição da terceira transação deve ser 'Depósito 2'", "Depósito 2", transaction3.getDescription());
    }
    
    /**
     * Testa a geração do extrato da conta.
     */
    public void testStatement() {
        // Realiza algumas transações
        account.deposit(200.0, "Depósito 1");
        account.withdraw(100.0, "Saque 1");
        
        // Obtém o extrato
        String statement = account.getStatement();
        
        assertNotNull("Extrato não deve ser nulo", statement);
        assertTrue("Extrato deve conter o ID da conta", statement.contains("ACC001"));
        assertTrue("Extrato deve conter o nome do titular", statement.contains("Teste da Silva"));
        assertTrue("Extrato deve conter o saldo atual", statement.contains("1100.0"));
        assertTrue("Extrato deve conter a descrição do depósito", statement.contains("Depósito 1"));
        assertTrue("Extrato deve conter a descrição do saque", statement.contains("Saque 1"));
    }
}
