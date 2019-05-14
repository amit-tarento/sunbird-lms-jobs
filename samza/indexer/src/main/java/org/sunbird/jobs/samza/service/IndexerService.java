package org.sunbird.jobs.samza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.samza.config.Config;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.jobs.samza.util.JSONUtils;
import org.sunbird.models.Constants;
import org.sunbird.models.Message;
import org.sunbird.models.MessageCreator;
import org.sunbird.validator.MessageValidator;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;

public class IndexerService {

  private static final String INDEX = ProjectUtil.EsIndex.sunbird.getIndexName();

  private static MessageCreator messageCreator = null;
  private static MessageValidator messageValidator = null;
  private final static String confFile = "indexer.conf";
  private static com.typesafe.config.Config config = null;
  private static Map<String, String> properties = null;
  private Config appConfig = null;

  public IndexerService() {
    messageCreator = new MessageCreator();
    messageValidator = new MessageValidator();
    initProps();
    ProjectLogger.log("IndexerService is intialised", LoggerEnum.INFO);
  }

  public void load(Config config) {
    JSONUtils.loadProperties(config);
    appConfig = config;
    ProjectUtil.propertiesCache.saveConfigProperty((JsonKey.SUNBIRD_ES_CLUSTER),
        appConfig.get(JsonKey.SUNBIRD_ES_CLUSTER));
    ProjectUtil.propertiesCache.saveConfigProperty((JsonKey.SUNBIRD_ES_IP),
        appConfig.get(JsonKey.SUNBIRD_ES_IP));
    ProjectUtil.propertiesCache.saveConfigProperty((JsonKey.SUNBIRD_ES_PORT),
        appConfig.get(JsonKey.SUNBIRD_ES_PORT));
  }

  private void initProps() {
    config = ConfigFactory.parseResources(confFile);
    properties = new HashMap<>();
    Set<Entry<String, ConfigValue>> configSet = config.entrySet();
    for (Entry<String, ConfigValue> confEntry : configSet) {
      properties.put(confEntry.getKey(), confEntry.getValue().unwrapped().toString());
    }
  }

  public void process(Map<String, Object> messageMap) {
    messageValidator.validateMessage(messageMap);

    Message message = messageCreator.getMessage(messageMap);

    String objectType = message.getObjectType();
    updateES(getIndex(objectType), getKey(objectType), message);
  }

  private void updateES(String type, String key, Message message) {
    Map<String, Object> properties = prepareData(message, type);
    if (!Constants.DELETE.equals(message.getOperationType())) {
      String identifier = (String) properties.get(key);
      ProjectLogger.log("IndexerService:updateES: Upsert data for identifier = " + identifier, LoggerEnum.INFO);
      ElasticSearchUtil.upsertData(INDEX, type, identifier, properties);
    } else {
      String identifier = (String) properties.get(key);
      ProjectLogger.log("IndexerService:updateES: Remove data for identifier = " + identifier, LoggerEnum.INFO);
      ElasticSearchUtil.removeData(INDEX, type, identifier);
    }
  }

  private Map<String, Object> prepareData(Message message, String type) {
    Map<String, Object> data = null;
    if (JsonKey.USER.equalsIgnoreCase(type)) {
      data = prepareUserData(message);
    } else {
      data = message.getProperties();
    }
    return data;
  }

  private Map<String, Object> prepareUserData(Message message) {
    Map<String, Object> data = null;
    data = message.getProperties();
    if (message.getObjectType().equals(JsonKey.USER)) {
      return data;
    } else {
      String objectType = message.getObjectType();
      String userId = (String) data.get(Constants.USER_ID);
      Map<String, Object> esMap = ElasticSearchUtil.getDataByIdentifier(INDEX, JsonKey.USER, userId);
      if (objectType.equalsIgnoreCase(Constants.USER_EDUCATION)) {
        data = updateNestedData(data, esMap, JsonKey.EDUCATION, Constants.ID, message.getOperationType());
      } else if (objectType.equalsIgnoreCase(Constants.USER_JOB_PROFILE)) {
        data = updateNestedData(data, esMap, JsonKey.JOB_PROFILE, Constants.ID, message.getOperationType());
      } else if (objectType.equalsIgnoreCase(Constants.USER_ORG)) {
        data = updateNestedData(data, esMap, JsonKey.ORGANISATIONS, Constants.ID, message.getOperationType());
      } else if (objectType.equalsIgnoreCase(Constants.USER_BADGE_ASSERTION)) {
        esMap.put(JsonKey.BADGE_ASSERTIONS, data);
        data = esMap;
      } else if (objectType.equalsIgnoreCase(Constants.USER_SKILLS)) {
        data = updateNestedData(data, esMap, JsonKey.SKILLS, Constants.ID, message.getOperationType());
      }
    }
    return data;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> updateNestedData(Map<String, Object> data, Map<String, Object> esMap, String attribute,
      String key, String operationType) {
    if (esMap.get(attribute) != null) {
      boolean isAlreadyPresent = updateIfAlreadyExist((List) esMap.get(attribute), data, attribute, key, operationType);
      if (!isAlreadyPresent) {
        ((List) esMap.get(attribute)).add(data);
      }
    } else {
      List<Map<String, Object>> list = new ArrayList<>();
      list.add(data);
      esMap.put(attribute, list);
      data = esMap;
    }
    return data;
  }
  private boolean updateIfAlreadyExist(List<Map<String, Object>> esDataList, Map<String, Object> data, String attribute,
      String id, String operationType) {
    for (Map<String, Object> esData : esDataList) {
      if (esData.get(id).equals(data.get(id))) {
        if (operationType.equalsIgnoreCase(Constants.DELETE)
            || (data.get(JsonKey.IS_DELETED) != null && (boolean) data.get(JsonKey.IS_DELETED))) {
          esDataList.remove(esData);
        } else {
          esData = data;
        }
        return true;
      }
    }
    return false;
  }

  private String getIndex(String objectType) {
    String key = objectType + Constants.DOT + Constants.INDEX;
    if (properties.containsKey(key)) {
      return properties.get(key);
    }
    return null;
  }

  private String getKey(String objectType) {
    String key = objectType + Constants.DOT + Constants.KEY;
    if (properties.containsKey(key)) {
      return properties.get(key);
    }
    return null;
  }
}
