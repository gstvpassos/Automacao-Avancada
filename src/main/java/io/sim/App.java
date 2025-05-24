package io.sim;

import java.util.logging.Logger;

/**
 * Classe principal da aplicação SUMO Simulator.
 * Responsável apenas por iniciar o EnvSimulator e aguardar sua conclusão.
 */
public class App {
    private static final Logger logger = Logger.getLogger(App.class.getName());

    /**
     * Método principal da aplicação.
     * 
     * @param args Argumentos de linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        logger.info("Iniciando aplicação SUMO Simulator");
        
        try {
            // Cria o simulador de ambiente com configurações padrão
            EnvSimulator envSimulator = new EnvSimulator();
            
            // Inicia o simulador em uma thread separada
            envSimulator.start();
            
            // Aguarda a conclusão do simulador usando join
            envSimulator.join();
            
            logger.info("Aplicação SUMO Simulator concluída com sucesso");
            
        } catch (InterruptedException e) {
            logger.warning("Aplicação SUMO Simulator interrompida: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.severe("Erro na aplicação SUMO Simulator: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
