package io.sim;

import sim.traci4j.src.java.it.polito.appeal.traci.Repository;
import sim.traci4j.src.java.it.polito.appeal.traci.Edge;
import sim.traci4j.src.java.it.polito.appeal.traci.Lane;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.tudresden.sumo.cmd.Vehicle;
import de.tudresden.sumo.objects.SumoColor;
import de.tudresden.sumo.objects.SumoPosition2D;
import de.tudresden.sumo.objects.SumoStringList;
import it.polito.appeal.traci.SumoTraciConnection;

import io.sim.reporting.ReportingSystem;
import io.sim.utils.JsonUtil;
import io.sim.utils.GeoUtils;

/**
 * Representa um veículo inteligente no sistema de simulação de mobilidade urbana.
 * 
 * <p>Esta classe implementa um veículo autônomo que:
 * <ul>
 *   <li>Coleta dados de sensores em tempo real do SUMO</li>
 *   <li>Gerencia consumo de combustível e abastecimento</li>
 *   <li>Comunica-se com sistemas bancários para pagamentos</li>
 *   <li>Reporta dados de condução para análise</li>
 *   <li>Executa em thread separada para operação concorrente</li>
 * </ul></p>
 * 
 * <p>O veículo utiliza sincronização adequada para operações thread-safe
 * com o simulador SUMO e outros componentes do sistema.</p>
 * 
 * @author Sistema SUMO Simulator
 * @version 1.0
 * @since 1.0
 */
public class Car extends sim.traci4j.src.java.it.polito.appeal.traci.Vehicle implements Runnable {

    private static final Logger logger = Logger.getLogger(Car.class.getName());
    private CountDownLatch readyLatch;

    // Constantes de configuração do veículo
    private static final double REFUEL_THRESHOLD = 3.0; // Limite para abastecimento (3 litros)
    private static final double INITIAL_FUEL = 10.0; // Combustível inicial (10 litros)
    private static final long REFUEL_TIME = 120000; // Tempo de abastecimento (2 minutos em milissegundos)

    // Identificação e configuração do veículo
    private String idCar;
    private SumoColor colorCar;
    private String driverID;
    private SumoTraciConnection sumo;
    
    // Objeto de sincronização para operações SUMO thread-safe
    private Object sumoLock;

    // Controle de execução da thread
    private boolean on_off;
    private long acquisitionRate;
    
    // Características do veículo
    private int fuelType;            // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    private int fuelPreferential;    // 1-diesel, 2-gasoline, 3-ethanol, 4-hybrid
    private double fuelPrice;        // price in liters
    private int personCapacity;      // the total number of persons that can ride in this vehicle
    private int personNumber;        // the total number of persons which are riding in this vehicle

    // Conexão com servidores
    private MobilityCompany companyServer; // Para enviar dados
    private int companyPort = 12346;
    private FuelStation fuelStation;       // Para solicitar abastecimento
    private Socket companyConnection;
    private ObjectOutputStream companyOut;
    private ObjectInputStream companyIn;
    private boolean connected;
    
    // Sistema de relatórios
    //private ReportingSystem reportingSystem;
    
    // Tanque de combustível
    private double fuelTank;
    private boolean refueling;
    private long refuelingStartTime;
    
    // Dados de condução e relatórios
    private ArrayList<DrivingData> drivingReport_LOCAL;
    private SumoPosition2D lastPosition;
    private double totalOdometer;
    private double distanceSinceLastRefuel;
    private double distanceSinceLastReport;
    
    // Conexão com Company
    private EncriptaDecriptaDES companySessionEncryptor;
    
    /**
     * Construtor da classe Car.
     * 
     * @param _dis DataInputStream
     * @param _dos DataOutputStream
     * @param _repoEdge Repositório de arestas
     * @param _repoLane Repositório de pistas
     * @param _on_off Estado inicial (ligado/desligado)
     * @param _idCar ID do carro
     * @param _colorCar Cor do carro
     * @param _driverID ID do motorista
     * @param _sumo Conexão com o SUMO
     * @param _sumoLock Objeto de sincronização para operações SUMO
     * @param _acquisitionRate Taxa de aquisição de dados
     * @param _fuelType Tipo de combustível
     * @param _fuelPreferential Tipo de combustível preferencial
     * @param _fuelPrice Preço do combustível
     * @param _personCapacity Capacidade de pessoas
     * @param _personNumber Número de pessoas
     * @throws Exception Se ocorrer um erro na inicialização
     */
    public Car(DataInputStream _dis, DataOutputStream _dos, Repository<Edge> _repoEdge, Repository<Lane> _repoLane, boolean _on_off, String _idCar, SumoColor _colorCar, String _driverID, SumoTraciConnection _sumo, 
            Object _sumoLock, long _acquisitionRate, int _fuelType, int _fuelPreferential, double _fuelPrice, 
            int _personCapacity, int _personNumber, CountDownLatch readyLatch) throws Exception {
        
        // Chama o construtor da classe pai (Vehicle)
        super(_dis, _dos, _idCar, _repoEdge, _repoLane);
        
        this.readyLatch = readyLatch;
        this.on_off = _on_off;
        this.idCar = _idCar;
        this.colorCar = _colorCar;
        this.driverID = _driverID;
        this.sumo = _sumo;
        this.sumoLock = _sumoLock;
        this.acquisitionRate = _acquisitionRate;     
        if((_fuelType < 0) || (_fuelType > 4)) {
            this.fuelType = 4;
        } else {
            this.fuelType = _fuelType;
        }
        
        if((_fuelPreferential < 0) || (_fuelPreferential > 4)) {
            this.fuelPreferential = 4;
        } else {
            this.fuelPreferential = _fuelPreferential;
        }

        this.fuelPrice = _fuelPrice;
        this.personCapacity = _personCapacity;
        this.personNumber = _personNumber;
        this.drivingReport_LOCAL = new ArrayList<>();
        
        // Inicializa o tanque de combustível com 10 litros
        this.fuelTank = INITIAL_FUEL;
        this.refueling = false;
        
        // Inicializa contadores de distância
        this.totalOdometer = 0.0;
        this.distanceSinceLastReport = 0.0;
        this.lastPosition = null;
        
        logger.info("Car " + idCar + " criado com " + INITIAL_FUEL + "L de combustível.");
    }

    /**
     * Conecta ao servidor da Company.
     * 
     * @param host Host do servidor
     * @param port Porta do servidor
     * @throws IOException Se ocorrer um erro de conexão
     */
    public void connectToCompany(String host, int port) throws IOException {
        if (this.connected) {
            logger.info("Car " + idCar + ": Já conectado à Company.");
            return;
        }
        try {
            logger.info("Car " + idCar + " conectando ao servidor Company em " + host + ":" + port);
            this.companyConnection = new Socket(host, port);
            this.companyOut = new ObjectOutputStream(companyConnection.getOutputStream());
            this.companyOut.flush();
            this.companyIn = new ObjectInputStream(companyConnection.getInputStream());
            logger.info("Car " + idCar + ": Streams para Company criadas.");

            // 1. REGISTRO INICIAL (envia CarRegistration JSON)
            CarRegistration registration = new CarRegistration(this.idCar, this.driverID, "CAR_CLIENT");
            String registrationJson = JsonUtil.toJson(registration);
            companyOut.writeObject(registrationJson);
            companyOut.flush();
            logger.info("Car " + idCar + ": Mensagem de registro JSON enviada: " + registrationJson);

            // 2. RECEBER CHAVE PÚBLICA RSA DA COMPANY
            String companyRsaPublicKeyBase64 = (String) companyIn.readObject();
            logger.info("Car " + idCar + ": Chave pública RSA da Company recebida.");
            PublicKey companyRsaPublicKey = EncriptaDecriptaRSA.getPublicKeyFromBase64(companyRsaPublicKeyBase64);

            // 3. GERAR CHAVE DE SESSÃO DES, CRIPTOGRAFAR E ENVIAR
            EncriptaDecriptaDES desKeyGenerator = new EncriptaDecriptaDES();
            byte[] sessionDesKeyBytes = desKeyGenerator.getChaveDESBytes();
            this.companySessionEncryptor = new EncriptaDecriptaDES(sessionDesKeyBytes);

            byte[] encryptedSessionDesKey = EncriptaDecriptaRSA.criptografarComPublicKey(sessionDesKeyBytes, companyRsaPublicKey);
            companyOut.writeObject(encryptedSessionDesKey);
            companyOut.flush();
            logger.info("Car " + idCar + ": Chave de sessão DES criptografada enviada para Company.");

            // 4. RECEBER CONFIRMAÇÃO SEGURA
            String encryptedConfirmation = (String) companyIn.readObject();
            String confirmationMessage = this.companySessionEncryptor.descriptografar(encryptedConfirmation);

            if (!"REGISTRATION_SECURE_SUCCESS".equals(confirmationMessage)) {
                throw new IOException("Falha no handshake seguro com Company: " + confirmationMessage);
            }
            
            this.connected = true;
            logger.info("Car " + idCar + " conectado de forma SEGURA ao servidor Company.");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Car " + idCar + ": Erro ao conectar/negociar chave com Company: " + e.getMessage(), e);
            this.connected = false;
            this.companySessionEncryptor = null; // Garante que não tentará usar um encriptador inválido
            if (e instanceof IOException) throw (IOException)e;
            else throw new IOException("Erro na negociação de chave com Company: " + e.getMessage(), e);
        }
    }
    
    /**
     * Define a Fuel Station para este carro.
     * 
     * @param fuelStation Instância da Fuel Station
     */
    public void setFuelStation(FuelStation fuelStation) {
        this.fuelStation = fuelStation;
    }
    
	/**
     * Define o sistema de relatórios para este carro.
     * 
     * @param reportingSystem Sistema de relatórios a ser utilizado
     */
    // public void setReportingSystem(ReportingSystem reportingSystem) {
    //     this.reportingSystem = reportingSystem;
    // }
	
    /**
     * Define o servidor Company para este carro.
     * 
     * @param companyServer Instância do servidor Company
     */
    public void setCompanyServer(MobilityCompany companyServer) {
        this.companyServer = companyServer;
    }

    /**
     * Método principal da thread.
     * Gerencia a execução do carro, atualizando sensores e enviando dados.
     */

    @Override
    public void run() {
        // =================================================================
        // PARTE 1: INICIALIZAÇÃO E CONEXÃO
        // =================================================================
        boolean successfullyInitialized = false;
        int maxConnectionAttempts = 5; // Tenta conectar até 5 vezes
        long retryDelayMs = 1000; // Espera 1 segundo entre as tentativas

        // MUDANÇA #1: Adicionar um loop de retentativas para a conexão
        for (int attempt = 1; attempt <= maxConnectionAttempts; attempt++) {
            try {
                // Tenta conectar à MobilityCompany.
                connectToCompany("localhost", this.companyPort);

                if (isConnected()) {
                    logger.info("Car " + getIdCar() + " conectado com sucesso na tentativa " + attempt);
                    successfullyInitialized = true;
                    break; // Sucesso! Sai do loop de retentativas.
                }
            } catch (IOException e) {
                logger.warning("Car " + getIdCar() + " falhou na tentativa de conexão " + attempt + "/" + maxConnectionAttempts + ": " + e.getMessage());
                if (attempt < maxConnectionAttempts) {
                    try {
                        Thread.sleep(retryDelayMs); // Espera antes de tentar novamente
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Se após todas as tentativas a inicialização falhou, encerra a thread.
        if (!successfullyInitialized) {
            logger.severe("Car " + getIdCar() + " falhou em conectar à Company após " + maxConnectionAttempts + " tentativas. Encerrando thread.");
            // Importante: NÃO chama o countDown() aqui.
            return;
        }

        // Se chegou aqui, a inicialização foi um SUCESSO.
        logger.info("Car " + getIdCar() + " inicializado e pronto para o trabalho.");
        
        // Sinaliza que está pronto APENAS em caso de sucesso.
        if (this.readyLatch != null) {
            this.readyLatch.countDown();
        }

        // =================================================================
        // PARTE 2: AGUARDAR A PARTIDA DO VEÍCULO NO SUMO (A NOVA LÓGICA)
        // =================================================================
        try {
            logger.info("Car " + idCar + ": Aguardando partida na simulação...");
            boolean hasDeparted = false;
            while (!hasDeparted && this.on_off) {
                synchronized (this.sumoLock) {
                    // Vehicle.getIDList() só retorna carros que estão ATIVOS na via.
                    // Esta é a verificação mais confiável para saber se o carro partiu.
                    hasDeparted = ((SumoStringList)this.sumo.do_job_get(Vehicle.getIDList())).contains(this.idCar);
                }
                if (!hasDeparted) {
                    Thread.sleep(500); // Espera meio segundo antes de checar de novo
                }
            }

            if (hasDeparted) {
            logger.info("Car " + idCar + ": PARTIU! Iniciando envio de telemetria.");
            } else {
            // Se saiu do loop sem ter partido, é porque a simulação foi encerrada.
            logger.warning("Car " + idCar + ": Não detectou a partida (simulação pode ter encerrado). Encerrando.");
            cleanup();
            return;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Car " + idCar + ": Erro enquanto aguardava a partida. Encerrando.", e);
            cleanup();
            return;
        }

        // =================================================================
        // PARTE 3: LOOP DE TRABALHO PRINCIPAL
        // Este código só será alcançado se a PARTE 1 for bem-sucedida.
        // =================================================================
        while (this.on_off) {
            try {
                if (refueling) {
                    handleRefueling();
                } else {
                    // Obtém os dados do SUMO. Este método deve ser seguro e não quebrar.
                    DrivingData newDataPoint = this.atualizaSensoresEObtemDados(); 
                    
                    if (newDataPoint != null) {
                        // Envia os dados para a MobilityCompany.
                        sendSingleDrivingDataToCompany(newDataPoint);
                    } else {
                        // Se não obteve dados, provavelmente o carro saiu da simulação.
                        logger.info("CAR_RUN (" + idCar + "): Não foi possível obter dados (carro pode ter finalizado a rota). Encerrando loop.");
                        this.on_off = false; // Define a flag para sair do loop.
                    }

                    // Pausa antes do próximo ciclo.
                    Thread.sleep(this.acquisitionRate);
                }
            } catch (InterruptedException e) {
                logger.warning("Car " + idCar + " thread interrompida.");
                this.on_off = false; // Garante a saída do loop.
                Thread.currentThread().interrupt(); // Boa prática para restaurar o status da interrupção.
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro inesperado no loop de trabalho do Car " + idCar + ": " + e.getMessage(), e);
                this.on_off = false; // Desliga o carro em caso de erro grave.
            }
        }

        // =================================================================
        // PARTE 4: LIMPEZA
        // =================================================================
        cleanup();
        logger.info("Thread do Car " + getIdCar() + " finalizada.");
    }

    /**
     * Atualiza os sensores do carro e coleta dados de condução.
     */
    public DrivingData atualizaSensoresEObtemDados() {
        String logPrefix = "CAR_SENSOR (" + this.idCar + "): ";
        DrivingData report = null;

        if (this.sumo == null || this.sumo.isClosed()) {
            logger.warning(logPrefix + "Conexão SUMO fechada ou nula. Desligando carro.");
            this.on_off = false;
            return null;
        }

        try {
            // Declara variáveis que serão usadas fora do bloco synchronized
            SumoPosition2D sumoPosition2D;
            String roadID_fromSUMO;
            String routeID_fromSUMO;
            double speed_fromSUMO;
            double odometerFromSUMOForRoute;
            double fuelConsumptionSim_fromSUMO;
            double co2Emission_fromSUMO;
            double hcEmission_fromSUMO;
            
            // Sincroniza todas as operações SUMO para evitar conflitos entre threads
            synchronized(sumoLock) {
                // Tenta uma operação básica para verificar se o veículo é conhecido
                // Se esta falhar com "not known", as outras também falharão.
                sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getSpeed(this.idCar)); // Teste de "conhecimento"

                sumoPosition2D = (SumoPosition2D) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getPosition(this.idCar));
                //Point2D currentAwtPosition = getPosition(); // Este método é da superclasse TraciObject -> Vehicle

                if (sumoPosition2D == null) { // Pode acontecer se o veículo foi removido entre os comandos
                     logger.warning(logPrefix + "Posição nula do SUMO (veículo pode ter sido removido inesperadamente). Desligando carro.");
                     this.on_off = false; 
                     return null;
                }
                
                // Verificação de NaN ou Infinito
                if (Double.isNaN(sumoPosition2D.x) || Double.isNaN(sumoPosition2D.y) ||
                    Double.isInfinite(sumoPosition2D.x) || Double.isInfinite(sumoPosition2D.y)) {
                    logger.warning(logPrefix + "Posição SUMO (sumoPosition2D) contém NaN ou Infinito. x=" + sumoPosition2D.x + ", y=" + sumoPosition2D.y + ". Desligando carro.");
                    this.on_off = false;
                    return null;
                }

                double distanceDelta = 0.0;
                if (lastPosition != null) {
                    distanceDelta = GeoUtils.calculateEuclideanDistance(sumoPosition2D.x, sumoPosition2D.y, lastPosition.x, lastPosition.y);
                    this.totalOdometer += distanceDelta;
                }
                this.lastPosition = sumoPosition2D;

                roadID_fromSUMO = (String) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getRoadID(this.idCar));
                routeID_fromSUMO = (String) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getRouteID(this.idCar));
                speed_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getSpeed(this.idCar));
                odometerFromSUMOForRoute = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getDistance(this.idCar));
                fuelConsumptionSim_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getFuelConsumption(this.idCar));
                co2Emission_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getCO2Emission(this.idCar));
                hcEmission_fromSUMO = (double) sumo.do_job_get(de.tudresden.sumo.cmd.Vehicle.getHCEmission(this.idCar));
            } // fim do bloco synchronized para operações SUMO

            double[] geoCoords = GeoUtils.convertToGeo(sumoPosition2D.x, sumoPosition2D.y);

        if (geoCoords == null || Double.isNaN(geoCoords[0]) || Double.isNaN(geoCoords[1])) {
            logger.warning(logPrefix + "GeoUtils.convertToGeo retornou inválido (null, NaN) para x=" + sumoPosition2D.x + ", y=" + sumoPosition2D.y + ". Desligando carro.");
            this.on_off = false;
            return null;
        }

            double fuelConsumedLiters = convertFuelConsumptionToLiters(fuelConsumptionSim_fromSUMO, this.acquisitionRate);
            updateFuelTank(fuelConsumedLiters);
            
            report = new DrivingData(
                    this.idCar, this.driverID, System.currentTimeMillis(), 
                    sumoPosition2D.x, sumoPosition2D.y, 
                    geoCoords,
                    roadID_fromSUMO, routeID_fromSUMO, speed_fromSUMO,
                    odometerFromSUMOForRoute, // Usando seu odômetro calculado
                    fuelConsumptionSim_fromSUMO, // Valor bruto do SUMO
                    calculateAverageFuelConsumption(), // Seu cálculo de média
                    this.fuelType, this.fuelPrice, 
                    co2Emission_fromSUMO, hcEmission_fromSUMO, 
                    this.personCapacity, this.personNumber
            );
            
            synchronized(drivingReport_LOCAL) {
                this.drivingReport_LOCAL.add(report);
            }

            if (this.fuelTank <= REFUEL_THRESHOLD && !refueling) {
                startRefueling();
            }
            // Não é necessário chamar setSpeedMode aqui em cada passo se não houver mudança.
            // if (!refueling && !this.sumo.isClosed()) {
            //     this.sumo.do_job_set(de.tudresden.sumo.cmd.Vehicle.setSpeedMode(this.idCar, 32)); 
            // }

        } catch (it.polito.appeal.traci.TraCIException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (errorMsg.contains(this.idCar.toLowerCase() + "' is not known") || errorMsg.contains("does not exist")) {
                logger.warning(logPrefix + "Veículo " + this.idCar + " não (ou não mais) conhecido pelo SUMO. Desligando carro. Erro: " + e.getMessage());
            } else {
                logger.log(Level.WARNING, logPrefix + "Erro TraCI ao atualizar sensores: " + e.getMessage(), e);
            }
            this.on_off = false; // Importante para parar o loop do carro
            return null;
        } catch (Exception e) { // Captura outras exceções
            logger.log(Level.SEVERE, logPrefix + "Erro GERAL INESPERADO ao atualizar sensores: " + e.getMessage(), e);
            this.on_off = false; // Desliga o carro em caso de erro grave
            return null; 
        }
        // logger.info(logPrefix + "DrivingData criado para timestamp: " + (report != null ? report.getTimeStamp() : "NULL"));
        return report;
    }

    /**
     * Inicia a execução do carro como uma thread.
     */
    public void start() {
        Thread t = new Thread(this);
        t.start();
    }

        /**
     * Envia dados de condução para o servidor Company.
     */
    private void sendSingleDrivingDataToCompany(DrivingData dataPoint) {
        String logPrefix = "CAR_SEND (" + idCar + "): ";
        if (dataPoint == null) {
            logger.warning(logPrefix + "dataPoint é nulo, não pode enviar.");
            return;
        }
        if (!this.connected) {
            logger.warning(logPrefix + "NÃO CONECTADO à Company. Dados NÃO ENVIADOS para timestamp: " + dataPoint.getTimeStamp());
            return;
        }
        if (this.companySessionEncryptor == null) {
             logger.severe(logPrefix + "ERRO CRÍTICO - companySessionEncryptor NULO ao tentar enviar dados. Handshake com Company falhou? Dados NÃO ENVIADOS para timestamp: " + dataPoint.getTimeStamp());
             this.connected = false; // Marcar para possível reconexão
             return;
        }

        try {
            String reportJson = JsonUtil.toJson(dataPoint);
            // logger.fine(logPrefix + "JSON para Company: " + reportJson);
            String encryptedReportJson = this.companySessionEncryptor.criptografar(reportJson);
            // logger.fine(logPrefix + "JSON Criptografado (início): " + encryptedReportJson.substring(0, Math.min(encryptedReportJson.length(), 30)) + "...");
            
            if (companyOut == null) {
                logger.severe(logPrefix + "companyOut é NULO. Impossível enviar dados.");
                cleanup(); // Tenta limpar e força reconexão
                return;
            }

            companyOut.writeObject(encryptedReportJson);
            companyOut.flush();
            logger.info(logPrefix + "DrivingData CRIPTOGRAFADO enviado para Company (Timestamp: " + dataPoint.getTimeStamp() + ")");
        } catch (java.net.SocketException se) {
            logger.log(Level.SEVERE, logPrefix + "SocketException ao enviar DrivingData: " + se.getMessage() + ". Conexão provavelmente perdida.", se);
            logger.info("CAR_SEND (" + idCar + "): Enviando TS: " + dataPoint.getTimeStamp() + ". Encryptor OK? " + (this.companySessionEncryptor != null));
            cleanup();
        } catch (Exception e) {
            logger.log(Level.SEVERE, logPrefix + "FALHA ao enviar DrivingData criptografado: " + e.getMessage(), e);
            cleanup(); 
        }
    }
    
    /**
     * Inicia o processo de abastecimento.
     */
    private void startRefueling() {
        try {
            if (fuelStation == null) {
                logger.warning("Car " + idCar + " precisa abastecer, mas não há Fuel Station configurada");
                return;
            }
            
            logger.info("Car " + idCar + " iniciando abastecimento. Combustível atual: " + fuelTank + " litros");
            
            // Para o veículo
            sumo.do_job_set(Vehicle.setSpeed(this.idCar, 0));
            
            // Marca como abastecendo
            this.refueling = true;
            this.refuelingStartTime = System.currentTimeMillis();
            
            // Solicita abastecimento à Fuel Station
            // Nota: A implementação completa dependeria da interface da FuelStation
            // Por enquanto, apenas simulamos o abastecimento
            double amountToRefuel = INITIAL_FUEL - fuelTank;
            logger.info("Car " + idCar + " solicitando abastecimento de " + amountToRefuel + " litros");
            
            // Em uma implementação real, chamaria um método da FuelStation
            // fuelStation.requestRefuel(this, amountToRefuel);
        } catch (Exception e) {
            logger.severe("Erro ao iniciar abastecimento do carro " + idCar + ": " + e.getMessage());
            this.refueling = false;
        }
    }
    
    /**
     * Gerencia o processo de abastecimento em andamento.
     */
    private void handleRefueling() throws InterruptedException {
        // Verifica se o tempo de abastecimento já passou (2 minutos)
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - refuelingStartTime;
        
        if (elapsedTime >= REFUEL_TIME) {
            // Abastecimento concluído
            completeRefueling();
        } else {
            // Ainda abastecendo, mantém o veículo parado
            try {
                sumo.do_job_set(Vehicle.setSpeed(this.idCar, 0));
            } catch (Exception e) {
                logger.warning("Erro ao manter veículo parado durante abastecimento: " + e.getMessage());
            }
            
            // Aguarda um pouco antes de verificar novamente
            Thread.sleep(1000);
        }
    }
    
    /**
     * Finaliza o processo de abastecimento.
     */
    private void completeRefueling() {
        try {
            // Simula o abastecimento completo
            this.fuelTank = INITIAL_FUEL;
            this.refueling = false;
            
            logger.info("Car " + idCar + " concluiu o abastecimento. Combustível atual: " + fuelTank + " litros");
            
            // Em uma implementação real, processaria o pagamento aqui
            // Isso seria feito pelo Driver através do BotPayment
        } catch (Exception e) {
            logger.severe("Erro ao completar abastecimento do carro " + idCar + ": " + e.getMessage());
        }
    }
    
    /**
     * Atualiza o nível do tanque de combustível com base no consumo.
     * 
     * @param consumedLiters Quantidade de litros consumidos
     */
    private void updateFuelTank(double consumedLiters) {
        // Atualiza o nível do tanque
        this.fuelTank -= consumedLiters;
        
        // Garante que não fique negativo
        if (this.fuelTank < 0) {
            this.fuelTank = 0;
        }
    }
    
    /**
     * Converte o consumo de combustível de mg/s para litros.
     * 
     * @param fuelConsumptionMgPerS Consumo em mg/s
     * @param timeMs Tempo em milissegundos
     * @return Consumo em litros
     */
    private double convertFuelConsumptionToLiters(double fuelConsumptionMgPerS, long timeMs) {
        // Converte mg/s para g/s
        double fuelConsumptionGPerS = fuelConsumptionMgPerS / 1000.0;
        
        // Converte g/s para g no período
        double fuelConsumptionG = fuelConsumptionGPerS * (timeMs / 1000.0);
        
        // Converte g para litros (densidade aproximada da gasolina: 750g/L)
        double densityGPerL = 750.0;
        return fuelConsumptionG / densityGPerL;
    }
    
    /**
     * Calcula o consumo médio de combustível.
     * 
     * @return Consumo médio em km/L
     */
    private double calculateAverageFuelConsumption() {
        if (drivingReport_LOCAL.isEmpty()) {
            return 0.0;
        }
        
        // Calcula a média dos últimos relatórios
        double totalConsumption = 0.0;
        int count = 0;
        int maxSamples = Math.min(10, drivingReport_LOCAL.size());
        
        for (int i = drivingReport_LOCAL.size() - 1; i >= drivingReport_LOCAL.size() - maxSamples; i--) {
            totalConsumption += drivingReport_LOCAL.get(i).getFuelConsumption();
            count++;
        }
        
        return (count > 0) ? totalConsumption / count : 0.0;
    }
    
    /**
     * Calcula a distância entre duas posições.
     * 
     * @param pos1 Primeira posição
     * @param pos2 Segunda posição
     * @return Distância em metros
     */
    public double calculateDistance(SumoPosition2D pos1, SumoPosition2D pos2) {
        // Cálculo da distância euclidiana
        double dx = pos2.x - pos1.x;
        double dy = pos2.y - pos1.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Limpa recursos ao encerrar o carro.
     */
    public void cleanup() {
        // Fecha conexão com a Company
        try {
            if (companyOut != null) companyOut.close();
            if (companyIn != null) companyIn.close();
            if (companyConnection != null && !companyConnection.isClosed()) {
                companyConnection.close();
            }
        } catch (IOException e) {
            logger.warning("Erro ao fechar conexões: " + e.getMessage());
        }
        
        logger.info("Car " + idCar + " finalizado");
    }
    
    /**
     * Classe interna para representar uma solicitação de registro.
     */
    public static class CarRegistration {
        private String carId;
        private String driverId;
        private String clientType;
        
        public CarRegistration() {
        }

        public CarRegistration(String carId, String driverId, String clientType) {
            this.carId = carId;
            this.driverId = driverId;
            this.clientType = clientType;
        }
        
        // Getters necessários para serialização JSON
        public String getCarId() {
            return carId;
        }
        
        public String getDriverId() {
            return driverId;
        }
        
        public String getClientType() {
            return clientType;
        }

        public void setCarId(String carId) { this.carId = carId; }
        public void setDriverId(String driverId) { this.driverId = driverId; }
        public void setClientType(String clientType) { this.clientType = clientType; }
    }
    
    // Getters e Setters
    
    public boolean isOn_off() {
        return this.on_off;
    }

    public void setOn_off(boolean _on_off) {
        this.on_off = _on_off;
    }

    public long getAcquisitionRate() {
        return this.acquisitionRate;
    }

    public void setAcquisitionRate(long _acquisitionRate) {
        this.acquisitionRate = _acquisitionRate;
    }

    public String getIdCar() {
        return this.idCar;
    }

    public void setIdCar(String idCar) {
        this.idCar = idCar;
    }

    public SumoColor getColorCar() {
        return colorCar;
    }

    public void setColorCar(SumoColor colorCar) {
        this.colorCar = colorCar;
    }

    public String getDriverID() {
        return driverID;
    }

    public void setDriverID(String driverID) {
        this.driverID = driverID;
    }

    public SumoTraciConnection getSumo() {
        return this.sumo;
    }

    public void setSumo(SumoTraciConnection sumo) {
        this.sumo = sumo;
    }

    public int getFuelType() {
        return this.fuelType;
    }

    public void setFuelType(int _fuelType) {
        if((_fuelType < 0) || (_fuelType > 4)) {
            this.fuelType = 4;
        } else {
            this.fuelType = _fuelType;
        }
    }

    public double getFuelPrice() {
        return this.fuelPrice;
    }

    public void setFuelPrice(double _fuelPrice) {
        this.fuelPrice = _fuelPrice;
    }

    public SumoColor getcolorCar() {
        return this.colorCar;
    }

    public int getFuelPreferential() {
        return this.fuelPreferential;
    }

    public void setFuelPreferential(int _fuelPreferential) {
        if((_fuelPreferential < 0) || (_fuelPreferential > 4)) {
            this.fuelPreferential = 4;
        } else {
            this.fuelPreferential = _fuelPreferential;
        }
    }

    public int getPersonCapacity() {
        return this.personCapacity;
    }

    public int getPersonNumber() {
        return this.personNumber;
    }

    public void setPersonCapacity(int personCapacity) {
        this.personCapacity = personCapacity;
    }

    public void setPersonNumber(int personNumber) {
        this.personNumber = personNumber;
    }

    public ArrayList<DrivingData> getDrivingRepport() { // Mantém para o Driver pegar no final da rota
        return new ArrayList<>(this.drivingReport_LOCAL); // Retorna uma cópia
    }

    public void clearDrivingRepport() { // Usado pelo Driver após processar os dados de uma rota
        this.drivingReport_LOCAL.clear();
        this.lastPosition = null; 
        // this.distanceSinceLastReport = 0.0; // Se você tinha esse campo, resete-o.
        logger.info("Car " + idCar + ": Relatório de condução local (drivingReport_LOCAL) limpo.");
    }

    public void setDrivingRepport(ArrayList<DrivingData> drivingRepport) {
        this.drivingReport_LOCAL = drivingRepport;
    }
    
    public double getFuelTank() {
        return fuelTank;
    }
    
	public void setFuelTank(double fuelTank){
		this.fuelTank = fuelTank;
	}

    public boolean isRefueling() {
        return refueling;
    }
    
	public void setRefueling(boolean refueling){
		this.refueling = refueling;
	}

    public double getTotalDistance() {
        return totalOdometer;
    }
    
    public boolean isConnected() {
        return connected;
    }
}
