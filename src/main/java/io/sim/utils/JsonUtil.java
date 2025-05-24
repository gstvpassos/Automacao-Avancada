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

/**
 * Classe utilitária para manipulação de JSON usando a biblioteca org.json.
 * Fornece métodos para converter objetos para JSON e vice-versa.
 */
public class JsonUtil {

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
            return null;
        }
        
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            return jsonObjectToClass(jsonObject, clazz);
        } catch (JSONException e) {
            System.err.println("Erro ao converter JSON para objeto: " + e.getMessage());
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
            // Cria uma nova instância da classe
            T instance = clazz.getDeclaredConstructor().newInstance();
            
            // Obtém todos os campos declarados na classe
            Field[] fields = clazz.getDeclaredFields();
            
            // Itera sobre as chaves do JSONObject
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                
                // Procura um campo correspondente na classe
                for (Field field : fields) {
                    if (field.getName().equals(key)) {
                        try {
                            // Torna o campo acessível mesmo se for privado
                            field.setAccessible(true);
                            
                            // Obtém o valor do JSONObject
                            Object value = jsonObject.get(key);
                            
                            // Converte o valor para o tipo do campo, se necessário
                            if (value instanceof JSONObject && !field.getType().equals(JSONObject.class)) {
                                // Se o valor é um JSONObject e o campo não é do tipo JSONObject,
                                // tenta converter para o tipo do campo
                                value = jsonObjectToClass((JSONObject) value, field.getType());
                            } else if (value instanceof JSONArray && !field.getType().equals(JSONArray.class)) {
                                // Se o valor é um JSONArray e o campo não é do tipo JSONArray,
                                // tenta converter para uma List ou array
                                if (List.class.isAssignableFrom(field.getType())) {
                                    value = jsonArrayToList((JSONArray) value, field);
                                } else if (field.getType().isArray()) {
                                    // Implementação para arrays seria mais complexa
                                    // e não está incluída nesta versão simplificada
                                }
                            }
                            
                            // Define o valor no campo
                            field.set(instance, value);
                            break;
                        } catch (IllegalAccessException | JSONException e) {
                            System.err.println("Erro ao definir campo " + key + ": " + e.getMessage());
                        }
                    }
                }
            }
            
            return instance;
        } catch (Exception e) {
            System.err.println("Erro ao criar instância da classe " + clazz.getName() + ": " + e.getMessage());
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
                } else {
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
                    list.add(jsonArrayToList((JSONArray) value));
                } else {
                    list.add(value);
                }
            }
        } catch (JSONException e) {
            System.err.println("Erro ao converter JSONArray para List: " + e.getMessage());
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

        public GenericMessage() {
            // Construtor vazio necessário para deserialização
        }

        public GenericMessage(String type, String content) {
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters e Setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        @Override
        public String toString() {
            return "GenericMessage{" +
                   "type='" + type + '\'' +
                   ", content='" + content + '\'' +
                   ", timestamp=" + timestamp +
                   '}';
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
