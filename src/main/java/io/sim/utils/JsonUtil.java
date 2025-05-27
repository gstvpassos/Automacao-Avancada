package io.sim.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Classe utilitária para manipulação de JSON usando a biblioteca org.json.
 * Fornece métodos para converter objetos para JSON e vice-versa.
 */
public class JsonUtil {
    private static final Logger logger = Logger.getLogger(JsonUtil.class.getName());

    /**
     * Converte um objeto para uma String JSON.
     *
     * @param object O objeto a ser convertido.
     * @return Uma string representando o objeto em formato JSON.
     */
    public static String toJson(Object object) {
        if (object == null) {
            return "{}";
        }
        
        try {
            if (object instanceof Map) {
                // Converte Map para JSONObject
                return new JSONObject((Map<?, ?>) object).toString();
            } else if (object instanceof List) {
                // Converte List para JSONArray
                return new JSONArray((List<?>) object).toString();
            } else if (object instanceof String && ((String) object).trim().startsWith("{") && ((String) object).trim().endsWith("}")) {
                // Já é uma string JSON
                return (String) object;
            } else if (object.getClass().isArray()) {
                // Converte array para JSONArray
                return new JSONArray(object).toString();
            } else {
                // Converte POJO para JSONObject usando reflexão
                return objectToJsonObject(object).toString();
            }
        } catch (JSONException e) {
            System.err.println("Erro ao converter objeto para JSON: " + e.getMessage());
            return "{ \"error\": \"Falha na conversão para JSON: " + e.getMessage() + "\" }";
        }
    }

    /**
     * Converte um objeto para JSONObject usando reflexão.
     *
     * @param object O objeto a ser convertido.
     * @return Um JSONObject representando o objeto.
     */
    private static JSONObject objectToJsonObject(Object object) {
        JSONObject jsonObject = new JSONObject();
        
        // Obtém todos os campos declarados na classe do objeto
        Field[] fields = object.getClass().getDeclaredFields();
        
        for (Field field : fields) {
            try {
                // Torna o campo acessível mesmo se for privado
                field.setAccessible(true);
                
                // Obtém o valor do campo
                Object value = field.get(object);
                
                // Adiciona o campo ao JSONObject
                if (value != null) {
                    jsonObject.put(field.getName(), value);
                }
            } catch (IllegalAccessException | JSONException e) {
                System.err.println("Erro ao acessar campo " + field.getName() + ": " + e.getMessage());
            }
        }
        
        return jsonObject;
    }

    /**
     * Converte uma String JSON para um objeto da classe especificada.
     *
     * @param jsonString A string JSON a ser convertida.
     * @param clazz A classe do objeto de destino.
     * @return Uma instância da classe de destino, ou null em caso de erro.
     */
    public static <T> T fromJson(String jsonString, Class<T> clazz) {
        if (jsonString == null || jsonString.isEmpty()) {
            System.err.println("JsonUtil.fromJson: String JSON nula ou vazia.");
            return null;
        }
        
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            return jsonObjectToClass(jsonObject, clazz);
        } catch (JSONException e) {
            System.err.println("JsonUtil.fromJson: Erro ao parsear string JSON para JSONObject: " + e.getMessage() + "\nJSON String: " + jsonString);
            return null;
        }
    }

    /**
     * Converte um JSONObject para um objeto da classe especificada usando reflexão.
     *
     * @param jsonObject O JSONObject a ser convertido.
     * @param clazz A classe do objeto de destino.
     * @return Uma instância da classe de destino, ou null em caso de erro.
     */
    private static <T> T jsonObjectToClass(JSONObject jsonObject, Class<T> clazz) {
        try {
            T instance = clazz.getDeclaredConstructor().newInstance(); // Funciona pois DrivingData tem construtor vazio
            Field[] declaredFields = clazz.getDeclaredFields(); // Usar getDeclaredFields para incluir privados

            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Field field = null;
                try {
                    field = clazz.getDeclaredField(key); // Obter o campo pelo nome da chave
                    field.setAccessible(true);
                    Object valueJson = jsonObject.get(key);

                    if (JSONObject.NULL.equals(valueJson)) {
                        // Só definir como null se o tipo do campo não for primitivo
                        if (!field.getType().isPrimitive()) {
                            field.set(instance, null);
                        } else {
                            // Para tipos primitivos, não se pode atribuir null.
                            // Pode-se deixar o valor padrão do primitivo (ex: 0 para int/double)
                            // ou lançar um erro/logar, dependendo do comportamento desejado.
                            logger.warning("JsonUtil: Valor JSON NULO para o campo primitivo '" + key + "' na classe " + clazz.getName());
                        }
                        continue;
                    }

                    Class<?> fieldType = field.getType();

                    // Conversão explícita para tipos numéricos
                    if ((fieldType == double.class || fieldType == Double.class) && valueJson instanceof Number) {
                        field.set(instance, ((Number) valueJson).doubleValue());
                    } else if ((fieldType == int.class || fieldType == Integer.class) && valueJson instanceof Number) {
                        field.set(instance, ((Number) valueJson).intValue());
                    } else if ((fieldType == long.class || fieldType == Long.class) && valueJson instanceof Number) {
                        field.set(instance, ((Number) valueJson).longValue());
                    } else if ((fieldType == float.class || fieldType == Float.class) && valueJson instanceof Number) {
                        field.set(instance, ((Number) valueJson).floatValue());
                    } 
                    // Tratamento específico para double[] (como latLon)
                    else if (fieldType.isArray() && fieldType.getComponentType() == double.class && valueJson instanceof JSONArray) {
                        JSONArray jsonArray = (JSONArray) valueJson;
                        double[] doubleArray = new double[jsonArray.length()];
                        for (int i = 0; i < jsonArray.length(); i++) {
                            Object item = jsonArray.get(i);
                            if (item instanceof Number) {
                                doubleArray[i] = ((Number) item).doubleValue();
                            } else if (JSONObject.NULL.equals(item)) {
                                doubleArray[i] = Double.NaN; // Ou outra representação para nulo em array
                            } else {
                                logger.warning("JsonUtil: Item não numérico '" + item + "' (tipo: " + item.getClass().getName() + ") encontrado em JSONArray para campo double[] '" + key + "'");
                                doubleArray[i] = Double.NaN; // Valor padrão para erro
                            }
                        }
                        field.set(instance, doubleArray);
                    }
                    // Conversão recursiva para objetos POJO aninhados
                    else if (valueJson instanceof JSONObject && !fieldType.equals(JSONObject.class) && !Map.class.isAssignableFrom(fieldType)) {
                        field.set(instance, jsonObjectToClass((JSONObject) valueJson, fieldType));
                    } 
                    // Conversão para Listas genéricas (List<Object> ou List<Map<String,Object>>)
                    else if (valueJson instanceof JSONArray && List.class.isAssignableFrom(fieldType)) {
                        // O seu método jsonArrayToList já converte JSONObjects internos para Maps
                        field.set(instance, jsonArrayToList((JSONArray) valueJson));
                    }
                    // Atribuição direta para outros tipos (String, Boolean, etc.)
                    else {
                        field.set(instance, valueJson);
                    }

                } catch (NoSuchFieldException e) {
                    // Campo existe no JSON mas não na classe Java - pode ser ignorado
                    // logger.fine("JsonUtil: Campo JSON '" + key + "' não encontrado na classe " + clazz.getName() + ", ignorando.");
                } catch (IllegalArgumentException e) {
                    Object val = jsonObject.opt(key); // Usar opt para evitar outra JSONException se a chave sumir
                    String valType = (val == null || JSONObject.NULL.equals(val)) ? "null" : val.getClass().getName();
                    String fieldTypeName = (field != null) ? field.getType().getName() : "desconhecido";
                    logger.log(Level.SEVERE, "JsonUtil: Erro de tipo (IllegalArgumentException) ao definir campo '" + key + 
                                           "' para o valor '" + val + "' (tipo do valor JSON: " + valType + 
                                           ", tipo do campo Java: " + fieldTypeName + "): " + e.getMessage(), e);
                } catch (IllegalAccessException | JSONException e) { // Outras exceções de reflexão ou JSON
                     logger.log(Level.SEVERE, "JsonUtil: Erro ao definir campo '" + key + "' na classe " + clazz.getName() + ": " + e.getMessage(), e);
                }
            } // fim do while (keys.hasNext())
            return instance;
        } catch (Exception e) { // Erros como getDeclaredConstructor().newInstance()
            logger.log(Level.SEVERE, "JsonUtil: Erro GERAL ao criar/popular instância da classe " + clazz.getName() + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Converte um JSONArray para uma List.
     *
     * @param jsonArray O JSONArray a ser convertido.
     * @param field O campo que receberá a List.
     * @return Uma List contendo os elementos do JSONArray.
     */
    private static List<?> jsonArrayToList(JSONArray jsonArray, Field field) {
        List<Object> list = new ArrayList<>();
        
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(jsonArray.get(i));
            }
        } catch (JSONException e) {
            System.err.println("Erro ao converter JSONArray para List: " + e.getMessage());
        }
        
        return list;
    }

    /**
     * Converte uma String JSON para um Map.
     *
     * @param jsonString A string JSON a ser convertida.
     * @return Um Map representando o JSON, ou null em caso de erro.
     */
    public static Map<String, Object> jsonToMap(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return null;
        }
        
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            return jsonObjectToMap(jsonObject);
        } catch (JSONException e) {
            System.err.println("Erro ao converter JSON para Map: " + e.getMessage());
            return null;
        }
    }

    /**
     * Converte um JSONObject para um Map.
     *
     * @param jsonObject O JSONObject a ser convertido.
     * @return Um Map representando o JSONObject.
     */
    private static Map<String, Object> jsonObjectToMap(JSONObject jsonObject) {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = jsonObject.get(key);
                if (value instanceof JSONObject) {
                    map.put(key, jsonObjectToMap((JSONObject) value));
                } else if (value instanceof JSONArray) {
                    map.put(key, jsonArrayToList((JSONArray) value));
                } else if (JSONObject.NULL.equals(value)) {
                    map.put(key, null);
                }
                 else {
                    map.put(key, value);
                }
            } catch (JSONException e) {
                System.err.println("Erro ao obter valor para chave " + key + ": " + e.getMessage());
            }
        }
        return map;
    }

    /**
     * Converte um JSONArray para uma List.
     *
     * @param jsonArray O JSONArray a ser convertido.
     * @return Uma List contendo os elementos do JSONArray.
     */
    private static List<Object> jsonArrayToList(JSONArray jsonArray) {
        List<Object> list = new ArrayList<>();
        try {
            for (int i = 0; i < jsonArray.length(); i++) {
                Object value = jsonArray.get(i);
                if (value instanceof JSONObject) {
                    list.add(jsonObjectToMap((JSONObject) value));
                } else if (value instanceof JSONArray) {
                    list.add(jsonArrayToList((JSONArray) value)); // Chamada recursiva
                } else if (JSONObject.NULL.equals(value)) {
                    list.add(null);
                }
                 else {
                    list.add(value);
                }
            }
        } catch (JSONException e) {
            System.err.println("Erro ao converter JSONArray para List<Object>: " + e.getMessage());
        }
        return list;
    }

    /**
     * Verifica se uma string é um JSON válido.
     *
     * @param jsonString A string a ser verificada.
     * @return true se a string for um JSON válido, false caso contrário.
     */
    public static boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return false;
        }
        try {
            new JSONObject(jsonString);
            return true;
        } catch (JSONException e1) {
            try {
                new JSONArray(jsonString);
                return true;
            } catch (JSONException e2) {
                return false;
            }
        }
    }

    /**
     * Formata uma string JSON para melhor legibilidade.
     *
     * @param jsonString A string JSON a ser formatada.
     * @param indentFactor O fator de indentação (número de espaços).
     * @return A string JSON formatada, ou a string original em caso de erro.
     */

    public static String prettyPrint(String jsonString, int indentFactor) {
        if (jsonString == null || jsonString.isEmpty()) {
            return jsonString;
        }
        try {
            if (jsonString.trim().startsWith("{")) {
                JSONObject jsonObject = new JSONObject(jsonString);
                return jsonObject.toString(indentFactor);
            } else if (jsonString.trim().startsWith("[")) {
                JSONArray jsonArray = new JSONArray(jsonString);
                return jsonArray.toString(indentFactor);
            } else {
                return jsonString;
            }
        } catch (JSONException e) {
            System.err.println("Erro ao formatar JSON: " + e.getMessage());
            return jsonString;
        }
    }

    /**
     * Formata uma string JSON para melhor legibilidade com indentação padrão de 2 espaços.
     *
     * @param jsonString A string JSON a ser formatada.
     * @return A string JSON formatada, ou a string original em caso de erro.
     */
    public static String prettyPrint(String jsonString) {
        return prettyPrint(jsonString, 2);
    }

    /**
     * Exemplo de classe de mensagem para demonstração.
     */
    public static class GenericMessage {
        private String type;
        private String content;
        private long timestamp;
        public GenericMessage() {}
        public GenericMessage(String type, String content) {
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        @Override
        public String toString() {
            return "GenericMessage{" + "type='" + type + '\'' + ", content='" + content + '\'' + ", timestamp=" + timestamp + '}';
        }
    }

    /**
     * Método main para demonstração e teste.
     */
    public static void main(String[] args) {
        // Exemplo de serialização
        GenericMessage msg = new GenericMessage("TEST_MESSAGE", "Hello, JSON!");
        String jsonOutput = toJson(msg);
        System.out.println("Objeto para JSON: " + jsonOutput);
        System.out.println("JSON formatado:\n" + prettyPrint(jsonOutput));

        // Exemplo de deserialização
        String testJsonInput = "{\"type\":\"TEST_MESSAGE\",\"content\":\"Hello, JSON!\",\"timestamp\":1678886400000}";
        GenericMessage parsedMsg = fromJson(testJsonInput, GenericMessage.class);
        if (parsedMsg != null) {
            System.out.println("JSON para Objeto: " + parsedMsg);
            System.out.println("Tipo: " + parsedMsg.getType());
            System.out.println("Conteúdo: " + parsedMsg.getContent());
            System.out.println("Timestamp: " + parsedMsg.getTimestamp());
        } else {
            System.out.println("Falha ao parsear JSON.");
        }

        // Exemplo de conversão para Map
        Map<String, Object> map = jsonToMap(testJsonInput);
        if (map != null) {
            System.out.println("JSON para Map: " + map);
            System.out.println("Tipo do Map: " + map.get("type"));
            System.out.println("Conteúdo do Map: " + map.get("content"));
            System.out.println("Timestamp do Map: " + map.get("timestamp"));
        } else {
            System.out.println("Falha ao converter JSON para Map.");
        }

        // Exemplo de validação de JSON
        System.out.println("JSON válido? " + isValidJson(testJsonInput));
        System.out.println("String inválida é JSON válido? " + isValidJson("Isso não é JSON"));
    }
}
