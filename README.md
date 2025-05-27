# SUMO Simulator

## Visão Geral

O SUMO Simulator implementa um sistema completo de simulação de mobilidade urbana baseado no SUMO (Simulation of Urban MObility). Este projeto vai além da simulação básica de tráfego, integrando um ecossistema completo que inclui motoristas, veículos, empresas de transporte, sistema bancário e postos de combustível, todos interagindo em um ambiente urbano simulado.

## Arquitetura do Sistema

### Componentes Principais

#### Ambiente de Simulação (EnvSimulator)
O EnvSimulator atua como o núcleo do sistema, gerenciando o ciclo de vida da simulação e a interação com o SUMO. Ele é responsável por:
- Inicializar o servidor SUMO e estabelecer conexão via TraCI
- Carregar a malha viária e configurações de simulação
- Coordenar a execução sincronizada de todos os componentes
- Gerenciar o avanço do tempo de simulação
- Monitorar o status de todos os veículos e motoristas

#### Serviço de Transporte (TransportService)
O TransportService gerencia a distribuição e execução de rotas no sistema:
- Carrega e processa arquivos de rotas do SUMO
- Distribui rotas para motoristas de forma otimizada
- Monitora a execução das rotas e coleta estatísticas
- Coordena a comunicação entre motoristas e a empresa de mobilidade

#### Empresa de Mobilidade (MobilityCompany)
A MobilityCompany representa uma empresa de transporte que:
- Gerencia uma frota de veículos e motoristas
- Processa pagamentos e transações financeiras
- Gera relatórios de desempenho e custos
- Implementa estratégias de alocação de recursos

#### Motoristas (Driver)
Os motoristas são os operadores dos veículos no sistema:
- Recebem e executam rotas designadas
- Interagem com o sistema bancário para transações
- Gerenciam o abastecimento dos veículos
- Coletam e reportam dados de condução

#### Veículos (Car)
Os veículos representam os automóveis na simulação:
- Implementam comportamentos realistas de condução
- Monitoram consumo de combustível e estado do veículo
- Coletam dados de telemetria (posição, velocidade, aceleração)
- Interagem com a infraestrutura viária do SUMO

#### Rotas (Route)
As rotas definem os trajetos a serem percorridos:
- Contêm informações de origem, destino e pontos intermediários
- Armazenam dados sobre distância, tempo estimado e custo
- Mantêm histórico de execução e métricas de desempenho

#### Posto de Combustível (FuelStation)
O posto de combustível fornece serviços de abastecimento:
- Gerencia bombas de combustível e filas de atendimento
- Processa pagamentos pelo abastecimento
- Mantém registro de transações e níveis de estoque
- Implementa diferentes tipos de combustível e preços

#### Sistema Bancário (AlphaBank)
O AlphaBank implementa um sistema financeiro completo:
- Gerencia contas de usuários e entidades
- Processa transações seguras entre contas
- Mantém histórico de transações e extratos
- Implementa autenticação e autorização de operações

### Sistema Financeiro

#### Contas (Account)
Cada entidade no sistema possui uma conta bancária:
- Identificada por login e senha para autenticação
- Mantém saldo atual e histórico de transações
- Suporta operações de depósito, saque e transferência
- Gera extratos e relatórios financeiros

#### Pagamentos Automatizados (BotPayment)
O sistema de pagamentos automatizados:
- Processa transferências programadas entre contas
- Gerencia pagamentos recorrentes (como abastecimento)
- Mantém registro de todas as transações realizadas
- Implementa mecanismos de segurança para transações

#### Segurança Financeira
A segurança das transações é garantida por:
- Criptografia DES para comunicações seguras
- Autenticação de duas vias entre cliente e servidor
- Registro detalhado de todas as operações
- Verificação de saldo e autorização de transações

## Fluxo de Operação

### Inicialização do Sistema
1. O EnvSimulator inicia o servidor SUMO e estabelece conexão
2. A malha viária e configurações são carregadas
3. O AlphaBank é inicializado e as contas são criadas
4. A FuelStation é inicializada e configurada
5. A MobilityCompany é criada e configurada

### Preparação da Simulação
1. O TransportService carrega e processa as rotas disponíveis
2. Veículos são criados e adicionados à simulação
3. Motoristas são criados e associados aos veículos
4. Contas bancárias são criadas para cada entidade
5. A MobilityCompany distribui rotas para os motoristas

### Execução da Simulação
1. O EnvSimulator avança o tempo de simulação
2. Os motoristas conduzem os veículos seguindo suas rotas
3. Dados de condução são coletados em tempo real
4. Veículos consomem combustível e requerem abastecimento
5. Transações financeiras ocorrem entre entidades

### Abastecimento de Veículos
1. Quando o nível de combustível está baixo, o motorista solicita abastecimento
2. O veículo é direcionado para o posto de combustível mais próximo
3. A FuelStation processa a solicitação e realiza o abastecimento
4. O pagamento é processado automaticamente via AlphaBank
5. O veículo retoma sua rota após o abastecimento

### Finalização de Rotas
1. Ao completar uma rota, o motorista reporta à MobilityCompany
2. Dados de desempenho são registrados e analisados
3. Pagamentos são processados com base na execução da rota
4. Novas rotas podem ser atribuídas ao motorista
5. Relatórios são gerados com métricas de desempenho

## Comunicação entre Componentes

### Protocolo de Mensagens
Todos os componentes se comunicam através de mensagens JSON padronizadas:
- A classe JsonUtil garante consistência na serialização/deserialização
- Mensagens incluem cabeçalhos com tipo, origem, destino e timestamp
- O conteúdo das mensagens é estruturado de acordo com o tipo de operação
- Confirmações são enviadas para garantir entrega confiável

### Tipos de Mensagens
O sistema implementa diversos tipos de mensagens:
- **Mensagens de Controle**: Inicialização, configuração e encerramento
- **Mensagens de Estado**: Atualizações de posição, velocidade e combustível
- **Mensagens Financeiras**: Solicitações de pagamento e confirmações
- **Mensagens de Serviço**: Solicitações de abastecimento e manutenção
- **Mensagens de Relatório**: Dados de desempenho e estatísticas

## Coleta e Análise de Dados

### Dados de Condução (DrivingData)
Durante a simulação, são coletados dados detalhados de cada veículo:
- Posição geográfica (coordenadas x, y)
- Velocidade instantânea e média
- Aceleração e frenagem
- Consumo de combustível
- Tempo em movimento e parado
- Distância percorrida

### Dados Financeiros
O sistema registra todas as transações financeiras:
- Pagamentos por serviços de transporte
- Custos de abastecimento
- Taxas e impostos
- Receitas e despesas por entidade
- Balanços financeiros periódicos

### Sistema de Relatórios
Os dados coletados são processados para gerar relatórios detalhados:
- Relatórios de desempenho de motoristas e veículos
- Análise de eficiência de rotas e consumo
- Relatórios financeiros de receitas e despesas
- Estatísticas de utilização da malha viária
- Métricas de qualidade de serviço

## Implementação Técnica

O projeto é implementado em Java, utilizando:
- Integração com o SUMO via API TraCI
- Comunicação entre componentes via mensagens JSON
- Armazenamento de dados em estruturas otimizadas
- Geração de relatórios em formatos Excel e PDF
- Visualização de dados em tempo real

A arquitetura modular permite que cada componente opere de forma independente, mas coordenada, criando um ecossistema completo de simulação de mobilidade urbana.