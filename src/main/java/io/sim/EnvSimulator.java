package io.sim;

import sim.traci4j.src.java.it.polito.appeal.traci.ReadObjectVarQuery.StringListQ;
import sim.traci4j.src.java.it.polito.appeal.traci.Repository;
import sim.traci4j.src.java.it.polito.appeal.traci.protocol.Constants;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.tudresden.sumo.objects.SumoColor;
import io.sim.reporting.ReportingSystem;
import it.polito.appeal.traci.SumoTraciConnection;
import sim.traci4j.src.java.it.polito.appeal.traci.Edge;
import sim.traci4j.src.java.it.polito.appeal.traci.Lane;
import sim.traci4j.src.java.it.polito.appeal.traci.ReadObjectVarQuery.StringListQ;
import sim.traci4j.src.java.it.polito.appeal.traci.Repository;

/**
 * Classe responsável por gerenciar o ambiente de simulação.
 * Encapsula todos os parâmetros e lógica de simulação.
 */
public class EnvSimulator extends Thread {

    private static final Logger logger = Logger.getLogger(EnvSimulator.class.getName());
    
    // Constantes de configuração
    private int numDrivers = 100;
    private int numCars = 100;
    private int numRoutes = 200;
    private String routeFile = "data/dados2.xml";
    private String bankHost = "localhost";
    private int bankPort = 12345;
    private int companyPort = 12346; // Porta diferente para a MobilityCompany
    private int sumoPort = 8813;     // Porta padrão do SUMO, diferente das outras
    private double initialBalance = 1000.0;
    
    // Cores para os carros
    private final SumoColor[] CAR_COLORS = {
        new SumoColor(255, 0, 0, 126),    // Vermelho
        new SumoColor(0, 255, 0, 126),    // Verde
        new SumoColor(0, 0, 255, 126),    // Azul
        new SumoColor(255, 255, 0, 126),  // Amarelo
        new SumoColor(0, 255, 255, 126),  // Ciano
        new SumoColor(255, 0, 255, 126),  // Magenta
        new SumoColor(128, 128, 128, 126) // Cinza
    };
    
    // Tipos de combustível
    private final int[] FUEL_TYPES = {1, 2, 3, 4}; // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    
    // Preços de combustível
    private final double[] FUEL_PRICES = {5.20, 5.87, 4.59, 5.50};
    
    // Gerador de números aleatórios
    private final Random random = new Random();
    
    // Componentes da simulação
    private SumoTraciConnection sumo;
    private DataInputStream dis;
    private DataOutputStream dos;
    private Repository<Edge> repoEdge;
    private Repository<Lane> repoLane;
    private ReportingSystem reportingSystem;
    private AlphaBank alphaBank;
    private FuelStation fuelStation;
    private MobilityCompany mobilityCompany;
    
    // Lista de motoristas e serviços de transporte
    private List<Driver> drivers;
    private List<TransportService> transportServices;
    
    // Controle de simulação
    private CountDownLatch simulationCompleteLatch;
    private boolean simulationRunning = false;
    private Thread simulationThread;
    
    /**
     * Construtor padrão.
     */
    public EnvSimulator() {
        // Inicializa os streams e repositórios
        this.dis = new DataInputStream(new ByteArrayInputStream(new byte[0]));
        this.dos = new DataOutputStream(new ByteArrayOutputStream());
        
        // Inicializa o controle de simulação
        this.simulationCompleteLatch = new CountDownLatch(1);
        
        // Inicializa as listas
        this.drivers = new ArrayList<>();
        this.transportServices = new ArrayList<>();
    }
    
    /**
     * Construtor com parâmetros personalizados.
     * 
     * @param numDrivers Número de motoristas
     * @param numCars Número de carros
     * @param numRoutes Número de rotas
     * @param routeFile Arquivo de rotas
     * @param bankHost Host do banco
     * @param bankPort Porta do banco
     * @param companyPort Porta da empresa
     * @param sumoPort Porta do SUMO
     * @param initialBalance Saldo inicial
     */
    public EnvSimulator(int numDrivers, int numCars, int numRoutes, String routeFile,
                        String bankHost, int bankPort, int companyPort, int sumoPort, double initialBalance) {
        this();
        this.numDrivers = numDrivers;
        this.numCars = numCars;
        this.numRoutes = numRoutes;
        this.routeFile = routeFile;
        this.bankHost = bankHost;
        this.bankPort = bankPort;
        this.companyPort = companyPort;
        this.sumoPort = sumoPort;
        this.initialBalance = initialBalance;
    }
    
    /**
     * Método principal da thread.
     */
    @Override
    public void run() {
        logger.info("Iniciando simulação SUMO com " + numDrivers + " Drivers, " + 
                   numCars + " Cars e " + numRoutes + " Routes");
        
        try {
            // Inicializa todos os componentes
            initializeComponents();
            
            // Verifica se o SUMO está rodando
            if (sumo == null || sumo.isClosed()) {
                logger.severe("SUMO não está rodando. Abortando simulação.");
                return;
            }
            
            // Cria as rotas
            List<Rota> routes = createRoutes();
            
            // Verifica se há rotas válidas
            if (routes.isEmpty()) {
                logger.severe("Nenhuma rota válida encontrada. Abortando simulação.");
                return;
            }
            
            logger.info("Rotas válidas encontradas: " + routes.size());
            
            // Distribui as rotas para a empresa
            for (Rota rota : routes) {
                mobilityCompany.addRota(rota);
            }
            
            // Cria os carros e motoristas
            drivers = createDriversAndCars();
            
            // Distribui as rotas para os motoristas
            distributeRoutesToDrivers(drivers);
            logger.info("Distribuição de rotas concluída com sucesso");
            
            // Inicia o loop de simulação em uma thread separada
            // Movido para antes de iniciar os motoristas para garantir que a simulação já esteja rodando
            startSimulationLoop();
            logger.info("Loop de simulação iniciado com sucesso");
            
            // Pequena pausa para garantir que o loop de simulação esteja rodando
            Thread.sleep(1000);
            
            // Inicia todos os motoristas
            startDrivers(drivers);
            logger.info("Todos os motoristas iniciados com sucesso");
            
            // Cria e inicia os serviços de transporte para cada rota
            createAndStartTransportServices();
            logger.info("Todos os serviços de transporte iniciados com sucesso");
            
            // Aguarda a conclusão de todos os motoristas
            waitForDriversCompletion(drivers);
            
            // Aguarda a conclusão de todos os serviços de transporte
            waitForTransportServicesCompletion();
            
            // Gera relatórios finais
            generateFinalReports();
            
            logger.info("Simulação concluída com sucesso");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro durante a simulação", e);
        } finally {
            // Encerra todos os serviços
            shutdownServices();
        }
    }
    
    /**
     * Inicia o loop de simulação em uma thread separada.
     * Esta thread é responsável por avançar o tempo da simulação no SUMO.
     */
    private void startSimulationLoop() {
        logger.info("Iniciando loop de simulação SUMO");
        
        simulationRunning = true;
        simulationThread = new Thread(() -> {
            try {
                int step = 0;
                while (simulationRunning && !sumo.isClosed()) {
                    try {
                        // Avança a simulação em um passo
                        sumo.do_timestep();
                        step++;
                        
                        // Log a cada 100 passos para não sobrecarregar o log
                        if (step % 100 == 0) {
                            logger.info("Simulação SUMO: passo " + step + " executado");
                        }
                        
                        // Pequena pausa para não sobrecarregar o sistema
                        Thread.sleep(50);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Erro durante passo de simulação: " + e.getMessage(), e);
                        
                        // Verifica se o SUMO ainda está rodando
                        if (sumo.isClosed()) {
                            logger.severe("Conexão SUMO fechada. Encerrando loop de simulação.");
                            break;
                        }
                        
                        // Pausa maior em caso de erro para evitar spam de erros
                        Thread.sleep(1000);
                    }
                }
                logger.info("Loop de simulação SUMO encerrado após " + step + " passos");
            } catch (InterruptedException e) {
                logger.info("Loop de simulação SUMO interrompido");
                Thread.currentThread().interrupt();
            }
        }, "SUMO-Simulation-Loop");
        
        simulationThread.setDaemon(true);
        simulationThread.start();
        logger.info("Thread de loop de simulação SUMO iniciada");
    }
    
    /**
     * Inicializa todos os componentes da simulação.
     * 
     * @throws Exception Se ocorrer um erro durante a inicialização
     */
    private void initializeComponents() throws Exception {
        // Inicializa o sistema de relatórios
        initializeReportingSystem();
        
        // Inicializa o AlphaBank
        initializeAlphaBank();
        
        // Inicializa a conexão com o SUMO
        initializeSumoConnection();
        
        // Verifica se o SUMO está rodando
        if (sumo == null || sumo.isClosed()) {
            throw new Exception("Falha ao inicializar SUMO. Abortando simulação.");
        }
        
        // Inicializa os repositórios
        initializeRepositories();
        
        // Inicializa o posto de combustível
        initializeFuelStation();
        
        // Inicializa a empresa de mobilidade
        initializeMobilityCompany();
    }
    
    /**
     * Inicializa o sistema de relatórios.
     */
    private void initializeReportingSystem() {
        logger.info("Inicializando sistema de relatórios");
        reportingSystem = ReportingSystem.getInstance();
        reportingSystem.start();
    }
    
    /**
     * Inicializa a conexão com o SUMO.
     * 
     * @throws IOException Se ocorrer um erro ao iniciar o SUMO
     */
    private void initializeSumoConnection() throws IOException {
        logger.info("Inicializando conexão com o SUMO na porta " + sumoPort);
        
        String sumo_bin = "sumo-gui";
        String config_file = "map/map.sumo.cfg";
        
        try {
            sumo = new SumoTraciConnection(sumo_bin, config_file);
            
            // Adiciona opções de configuração
            sumo.addOption("start", "1");      // auto-run on GUI show
            sumo.addOption("quit-on-end", "0"); // não fechar automaticamente ao terminar
            sumo.addOption("step-length", "0.1"); // tamanho do passo de simulação
            sumo.addOption("verbose", "true"); // modo verboso para depuração
            
            // Inicia o servidor SUMO na porta especificada
            sumo.runServer(sumoPort);
            
            // Aguarda o SUMO iniciar
            Thread.sleep(5000); // Aumentado para 5 segundos para garantir inicialização completa
            
            // Verifica se o SUMO está rodando
            if (sumo.isClosed()) {
                logger.severe("SUMO não iniciou corretamente. Conexão fechada.");
                throw new IOException("SUMO não iniciou corretamente");
            }
            
            logger.info("SUMO iniciado com sucesso na porta " + sumoPort);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido durante a inicialização do SUMO", e);
        } catch (IOException e) {
            logger.severe("Erro ao iniciar SUMO: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Inicializa os repositórios Edge e Lane.
     */
    private void initializeRepositories() {
        logger.info("Inicializando repositórios Edge e Lane");
        
        try {
            // Inicializa os repositórios com os construtores específicos e parâmetros corretos
            repoEdge = new Repository.Edges(dis, dos,
                new StringListQ(dis, dos, Constants.CMD_GET_EDGE_VARIABLE, "edge", Constants.ID_LIST)
            );
            
            repoLane = new Repository.Lanes(dis, dos, repoEdge,
                new StringListQ(dis, dos, Constants.CMD_GET_LANE_VARIABLE, "lane", Constants.ID_LIST)
            );
            
            logger.info("Repositórios Edge e Lane inicializados com sucesso");
        } catch (Exception e) {
            logger.severe("Erro ao inicializar repositórios: " + e.getMessage());
        }
    }
    
    /**
     * Inicializa o AlphaBank.
     * 
     * @throws Exception Se ocorrer um erro ao iniciar o banco
     */
    private void initializeAlphaBank() throws Exception {
        try {
            logger.info("Inicializando AlphaBank na porta " + bankPort);
            alphaBank = new AlphaBank(bankPort);
            alphaBank.startServer();
            
            // Aguarda o banco iniciar
            Thread.sleep(2000);
            
            logger.info("AlphaBank iniciado com sucesso na porta " + bankPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.severe("Falha ao iniciar servidor do banco: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Inicializa o posto de combustível.
     */
    private void initializeFuelStation() {
        logger.info("Inicializando posto de combustível");
        
        // Cria uma conta para o posto
        Account fuelStationAccount = new Account("fuel_password", "fuel_station", 0.0);
        
        // Cria o posto de combustível
        fuelStation = new FuelStation("FS001", "Fuel Station", fuelStationAccount);
        fuelStation.start();
        
        logger.info("Posto de combustível iniciado com sucesso");
    }
    
    /**
     * Inicializa a empresa de mobilidade.
     */
    private void initializeMobilityCompany() {
        logger.info("Inicializando empresa de mobilidade na porta " + companyPort);
        
        // Cria uma conta para a empresa
        Account companyAccount = new Account("company_password", "mobility_company", 10000.0);
        
        // Cria a empresa de mobilidade com uma porta diferente do AlphaBank
        mobilityCompany = new MobilityCompany("MC001", companyPort, alphaBank, companyAccount, routeFile);
        mobilityCompany.start();
        
        logger.info("Empresa de mobilidade iniciada com sucesso na porta " + companyPort);
    }
    
    /**
     * Cria as rotas para a simulação.
     * 
     * @return Lista de rotas criadas
     */
    private List<Rota> createRoutes() {
        logger.info("Criando " + numRoutes + " rotas");
        
        List<Rota> routes = new ArrayList<>();
        
        try {
            for (int i = 1; i <= numRoutes; i++) {
                String routeId = "R" + String.format("%03d", i);
                Rota rota = new Rota(routeFile, routeId);
                
                // Verifica se a rota é válida
                if (rota.isOn()) {
                    routes.add(rota);
                    logger.fine("Rota " + routeId + " criada com sucesso");
                } else {
                    logger.warning("Rota " + routeId + " não é válida e será ignorada");
                }
            }
            
            logger.info("Total de rotas válidas criadas: " + routes.size());
        } catch (Exception e) {
            logger.severe("Erro ao criar rotas: " + e.getMessage());
        }
        
        return routes;
    }
    
    /**
     * Cria os motoristas e carros para a simulação.
     * 
     * @return Lista de motoristas criados
     * @throws Exception Se ocorrer um erro ao criar os motoristas e carros
     */
    private List<Driver> createDriversAndCars() throws Exception {
        logger.info("Criando " + numDrivers + " motoristas e " + numCars + " carros");
        
        List<Driver> driversList = new ArrayList<>();
        
        for (int i = 1; i <= numDrivers; i++) {
            String driverId = "D" + String.format("%03d", i);
            String driverName = "Driver " + i;
            String login = "driver" + i;
            String senha = "pass" + i;
            
            // Cria o carro para o motorista
            String carId = "CAR" + String.format("%03d", i);
            SumoColor carColor = CAR_COLORS[random.nextInt(CAR_COLORS.length)];
            int fuelTypeIndex = random.nextInt(FUEL_TYPES.length);
            int fuelType = FUEL_TYPES[fuelTypeIndex];
            int fuelPreferential = fuelType;
            double fuelPrice = FUEL_PRICES[fuelTypeIndex];
            int personCapacity = random.nextInt(4) + 1;
            int personNumber = random.nextInt(personCapacity) + 1;
            int acquisitionRate = 500;
            
            try {
                // Cria o carro com os parâmetros necessários para o Vehicle
                Car car = new Car(
                        dis, dos, repoEdge, repoLane,
                        true, carId, carColor, driverId, sumo, acquisitionRate,
                        fuelType, fuelPreferential, fuelPrice, personCapacity, personNumber
                );
                
                // Configura o sistema de relatórios no carro
                car.setReportingSystem(reportingSystem);
                
                // Configura o posto de combustível no carro
                car.setFuelStation(fuelStation);
                
                // Cria o motorista
                Driver driver = new Driver(
                        driverId, driverName, car, bankHost, bankPort, login, senha, initialBalance
                );
                
                // Adiciona o motorista à lista
                driversList.add(driver);
                
                logger.fine("Motorista " + driverId + " e carro " + carId + " criados com sucesso");
            } catch (Exception e) {
                logger.severe("Erro ao criar motorista " + driverId + " e carro: " + e.getMessage());
                throw e;
            }
        }
        
        logger.info("Total de " + driversList.size() + " motoristas e carros criados com sucesso");
        return driversList;
    }
    
    /**
     * Inicia todos os motoristas.
     * 
     * @param driversList Lista de motoristas
     */
    private void startDrivers(List<Driver> driversList) {
        logger.info("Iniciando " + driversList.size() + " motoristas");
        
        for (Driver driver : driversList) {
            try {
                // Inicia o motorista
                driver.start();
                
                // Pequeno delay para evitar sobrecarga
                Thread.sleep(50);
                
                logger.fine("Motorista " + driver.getDriverId() + " iniciado com sucesso");
            } catch (Exception e) {
                logger.warning("Erro ao iniciar motorista " + driver.getDriverId() + ": " + e.getMessage());
            }
        }
        
        logger.info("Todos os motoristas iniciados com sucesso");
    }
    
    /**
     * Cria e inicia os serviços de transporte para cada rota.
     */
    private void createAndStartTransportServices() {
        logger.info("Criando e iniciando serviços de transporte");
        
        int servicesCreated = 0;
        int servicesStarted = 0;
        
        // Cria um serviço de transporte para cada par carro/rota
        for (Driver driver : drivers) {
            Car car = driver.getCar();
            List<Rota> driverRoutes = driver.getRotasAExecutar();
            
            if (driverRoutes.isEmpty()) {
                logger.warning("Motorista " + driver.getDriverId() + " não tem rotas para executar");
                continue;
            }
            
            for (Rota rota : driverRoutes) {
                try {
                    // Cria um ID único para o serviço
                    String tsId = "TS_" + car.getIdCar() + "_" + rota.getIdRota();
                    
                    // Cria o serviço de transporte
                    TransportService ts = new TransportService(true, tsId, rota, car, sumo);
                    
                    // Adiciona à lista de serviços
                    transportServices.add(ts);
                    servicesCreated++;
                    
                    // Inicia o serviço
                    ts.start();
                    servicesStarted++;
                    
                    // Pequeno delay para evitar sobrecarga
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    logger.fine("Serviço de transporte " + tsId + " iniciado para carro " + car.getIdCar() + " e rota " + rota.getIdRota());
                } catch (Exception e) {
                    logger.warning("Erro ao criar serviço de transporte para carro " + car.getIdCar() + " e rota " + rota.getIdRota() + ": " + e.getMessage());
                }
            }
        }
        
        logger.info("Total de " + servicesCreated + " serviços de transporte criados e " + servicesStarted + " iniciados");
    }
    
    /**
     * Distribui as rotas para os motoristas.
     * Implementação corrigida para evitar loops infinitos.
     * 
     * @param driversList Lista de motoristas
     */
    private void distributeRoutesToDrivers(List<Driver> driversList) {
        logger.info("Distribuindo rotas para os motoristas");
        
        // Obtém as rotas da empresa
        List<Rota> availableRoutes = mobilityCompany.getAvailableRotas();
        
        if (availableRoutes.isEmpty()) {
            logger.warning("Não há rotas disponíveis para distribuir");
            return;
        }
        
        int totalRoutes = availableRoutes.size();
        logger.info("Total de rotas disponíveis para distribuição: " + totalRoutes);
        
        // Embaralha as rotas para distribuição aleatória
        Collections.shuffle(availableRoutes);
        
        // Distribuição determinística para evitar loops infinitos
        // Cada motorista recebe pelo menos 1 rota, e as rotas restantes são distribuídas sequencialmente
        int routesPerDriver = Math.max(1, totalRoutes / driversList.size());
        int remainingRoutes = totalRoutes % driversList.size();
        
        logger.info("Cada motorista receberá pelo menos " + routesPerDriver + " rotas, com " + 
                   remainingRoutes + " motoristas recebendo uma rota adicional");
        
        int routeIndex = 0;
        for (int i = 0; i < driversList.size(); i++) {
            Driver driver = driversList.get(i);
            
            // Número de rotas para este motorista
            int routesForThisDriver = routesPerDriver + (i < remainingRoutes ? 1 : 0);
            
            // Atribui as rotas ao motorista
            for (int j = 0; j < routesForThisDriver && routeIndex < totalRoutes; j++) {
                Rota rota = availableRoutes.get(routeIndex);
                driver.addRota(rota);
                routeIndex++;
                
                logger.info("Rota " + rota.getIdRota() + " atribuída ao motorista " + driver.getDriverId());
            }
        }
        
        // Verifica se todas as rotas foram distribuídas
        if (routeIndex < totalRoutes) {
            logger.warning("Nem todas as rotas foram distribuídas. Distribuindo as " + 
                          (totalRoutes - routeIndex) + " rotas restantes sequencialmente.");
            
            // Distribui as rotas restantes sequencialmente
            int driverIndex = 0;
            while (routeIndex < totalRoutes) {
                Driver driver = driversList.get(driverIndex % driversList.size());
                Rota rota = availableRoutes.get(routeIndex);
                driver.addRota(rota);
                routeIndex++;
                driverIndex++;
                
                logger.info("Rota adicional " + rota.getIdRota() + " atribuída ao motorista " + driver.getDriverId());
            }
        }
        
        // Registra a distribuição de rotas
        int totalRoutesDistributed = 0;
        for (Driver driver : driversList) {
            int driverRoutes = driver.getRotasAExecutar().size();
            totalRoutesDistributed += driverRoutes;
            logger.info("Motorista " + driver.getDriverId() + " recebeu " + driverRoutes + " rotas");
        }
        
        logger.info("Distribuição de rotas concluída. Total de " + totalRoutesDistributed + " rotas distribuídas.");
    }
    
    /**
     * Aguarda a conclusão de todos os motoristas.
     * 
     * @param driversList Lista de motoristas
     */
    private void waitForDriversCompletion(List<Driver> driversList) {
        logger.info("Aguardando a conclusão de todos os motoristas");
        
        // Usa o método join para aguardar a conclusão de todos os motoristas
        for (Driver driver : driversList) {
            try {
                driver.join();
                logger.fine("Motorista " + driver.getDriverId() + " concluído");
            } catch (InterruptedException e) {
                logger.warning("Interrupção ao aguardar o motorista " + driver.getDriverId());
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("Todos os motoristas concluídos");
    }
    
    /**
     * Aguarda a conclusão de todos os serviços de transporte.
     */
    private void waitForTransportServicesCompletion() {
        logger.info("Aguardando a conclusão de todos os serviços de transporte");
        
        // Usa o método join para aguardar a conclusão de todos os serviços
        for (TransportService ts : transportServices) {
            try {
                ts.join();
                logger.fine("Serviço de transporte " + ts.getIdTransportService() + " concluído");
            } catch (InterruptedException e) {
                logger.warning("Interrupção ao aguardar o serviço de transporte " + ts.getIdTransportService());
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("Todos os serviços de transporte concluídos");
    }
    
    /**
     * Gera relatórios finais da simulação.
     */
    private void generateFinalReports() {
        logger.info("Gerando relatórios finais");
        
        try {
            reportingSystem.generateFinalReports();
            logger.info("Relatórios finais gerados com sucesso");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Erro ao gerar relatórios finais", e);
        }
    }
    
    /**
     * Encerra todos os serviços.
     */
    private void shutdownServices() {
        logger.info("Encerrando todos os serviços");
        
        // Encerra o loop de simulação
        simulationRunning = false;
        if (simulationThread != null && simulationThread.isAlive()) {
            simulationThread.interrupt();
            try {
                simulationThread.join(5000); // Aguarda até 5 segundos
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Encerra todos os serviços de transporte
        for (TransportService ts : transportServices) {
            ts.setOn_off(false);
        }
        
        // Encerra o sistema de relatórios
        if (reportingSystem != null) {
            reportingSystem.stop();
        }
        
        // Encerra o posto de combustível
        if (fuelStation != null) {
            fuelStation.stopStation();
            try {
                fuelStation.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Encerra a empresa de mobilidade
        if (mobilityCompany != null) {
            mobilityCompany.stopServer(); // Método correto para encerrar o servidor
            try {
                mobilityCompany.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Encerra o AlphaBank
        if (alphaBank != null) {
            alphaBank.stopServer();
            try {
                alphaBank.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Encerra a conexão com o SUMO
        if (sumo != null && !sumo.isClosed()) {
            try {
                logger.info("Fechando conexão com o SUMO");
                sumo.close();
                logger.info("Conexão com o SUMO fechada com sucesso");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Erro ao fechar conexão com o SUMO", e);
            }
        }
        
        logger.info("Todos os serviços encerrados com sucesso");
    }
    
    /**
     * Sinaliza que a simulação foi concluída.
     */
    public void signalSimulationComplete() {
        simulationCompleteLatch.countDown();
    }
    
    /**
     * Obtém o sistema de relatórios.
     * 
     * @return Sistema de relatórios
     */
    public ReportingSystem getReportingSystem() {
        return reportingSystem;
    }
    
    /**
     * Obtém a lista de motoristas.
     * 
     * @return Lista de motoristas
     */
    public List<Driver> getDrivers() {
        return drivers;
    }
    
    /**
     * Obtém a lista de serviços de transporte.
     * 
     * @return Lista de serviços de transporte
     */
    public List<TransportService> getTransportServices() {
        return transportServices;
    }
    
    // Getters e setters para os parâmetros de configuração
    
    public int getNumDrivers() {
        return numDrivers;
    }
    
    public void setNumDrivers(int numDrivers) {
        this.numDrivers = numDrivers;
    }
    
    public int getNumCars() {
        return numCars;
    }
    
    public void setNumCars(int numCars) {
        this.numCars = numCars;
    }
    
    public int getNumRoutes() {
        return numRoutes;
    }
    
    public void setNumRoutes(int numRoutes) {
        this.numRoutes = numRoutes;
    }
    
    public String getRouteFile() {
        return routeFile;
    }
    
    public void setRouteFile(String routeFile) {
        this.routeFile = routeFile;
    }
    
    public String getBankHost() {
        return bankHost;
    }
    
    public void setBankHost(String bankHost) {
        this.bankHost = bankHost;
    }
    
    public int getBankPort() {
        return bankPort;
    }
    
    public void setBankPort(int bankPort) {
        this.bankPort = bankPort;
    }
    
    public int getCompanyPort() {
        return companyPort;
    }
    
    public void setCompanyPort(int companyPort) {
        this.companyPort = companyPort;
    }
    
    public int getSumoPort() {
        return sumoPort;
    }
    
    public void setSumoPort(int sumoPort) {
        this.sumoPort = sumoPort;
    }
    
    public double getInitialBalance() {
        return initialBalance;
    }
    
    public void setInitialBalance(double initialBalance) {
        this.initialBalance = initialBalance;
    }
}
