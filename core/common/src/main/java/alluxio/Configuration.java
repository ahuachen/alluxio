/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio;

import alluxio.exception.ExceptionMessage;
import alluxio.network.ChannelType;
import alluxio.util.ConfigurationUtils;
import alluxio.util.FormatUtils;
import alluxio.util.network.NetworkAddressUtils;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * <p>
 * All the runtime configuration properties of Alluxio. This class works like a dictionary and
 * serves each Alluxio configuration property as a key-value pair.
 *
 * <p>
 * Alluxio configuration properties are loaded into this class in the following order with
 * decreasing priority:
 * <ol>
 * <li>Java system properties;</li>
 * <li>Environment variables via {@code alluxio-env.sh} or from OS settings;</li>
 * <li>Site specific properties via {@code alluxio-site.properties} file;</li>
 * <li>Default properties via {@code alluxio-default.properties} file.</li>
 * </ol>
 *
 * <p>
 * The default properties are defined in a property file {@code alluxio-default.properties}
 * distributed with Alluxio jar. Alluxio users can override values of these default properties by
 * creating {@code alluxio-site.properties} and putting it under java {@code CLASSPATH} when running
 * Alluxio (e.g., ${ALLUXIO_HOME}/conf/)
 */
@NotThreadSafe
public final class Configuration {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** File to set default properties. */
  private static final String DEFAULT_PROPERTIES = "alluxio-default.properties";
  /** File to set customized properties for Alluxio server (both master and worker) and client. */
  private static final String SITE_PROPERTIES = "alluxio-site.properties";

  /** Regex string to find "${key}" for variable substitution. */
  private static final String REGEX_STRING = "(\\$\\{([^{}]*)\\})";
  /** Regex to find ${key} for variable substitution. */
  private static final Pattern CONF_REGEX = Pattern.compile(REGEX_STRING);
  /** Set of properties. */
  private static final Properties PROPERTIES = new Properties();

  static {
    defaultInit();
  }

  /**
   * The minimal configuration without loading any site or system properties.
   */
  public static void emptyInit() {
    init(null, false);
  }

  /**
   * The default configuration.
   */
  public static void defaultInit() {
    init(SITE_PROPERTIES, true);
  }

  /**
   * Constructor with a flag to indicate whether system properties should be included. When the flag
   * is set to false, it is used for {@link Configuration} test class.
   *
   * @param sitePropertiesFile site-wide properties
   * @param includeSystemProperties whether to include the system properties
   */
  private static void init(String sitePropertiesFile, boolean includeSystemProperties) {
    // Load default
    Properties defaultProps = ConfigurationUtils.loadPropertiesFromResource(DEFAULT_PROPERTIES);
    if (defaultProps == null) {
      throw new RuntimeException(
          ExceptionMessage.DEFAULT_PROPERTIES_FILE_DOES_NOT_EXIST.getMessage());
    }
    // Override runtime default
    defaultProps.setProperty(Constants.MASTER_HOSTNAME, NetworkAddressUtils.getLocalHostName(250));
    defaultProps.setProperty(Constants.WORKER_NETWORK_NETTY_CHANNEL,
        String.valueOf(ChannelType.defaultType()));
    defaultProps.setProperty(Constants.USER_NETWORK_NETTY_CHANNEL,
        String.valueOf(ChannelType.defaultType()));

    String confPaths;
    // If site conf is overwritten in system properties, overwrite the default setting
    if (System.getProperty(Constants.SITE_CONF_DIR) != null) {
      confPaths = System.getProperty(Constants.SITE_CONF_DIR);
    } else {
      confPaths = defaultProps.getProperty(Constants.SITE_CONF_DIR);
    }
    String[] confPathList = confPaths.split(",");

    // Load site specific properties file
    Properties siteProps = ConfigurationUtils
        .searchPropertiesFile(sitePropertiesFile, confPathList);

    // Load system properties
    Properties systemProps = new Properties();
    if (includeSystemProperties) {
      systemProps.putAll(System.getProperties());
    }

    // Now lets combine, order matters here
    PROPERTIES.putAll(defaultProps);
    if (siteProps != null) {
      PROPERTIES.putAll(siteProps);
    }
    PROPERTIES.putAll(systemProps);

    String masterHostname = PROPERTIES.getProperty(Constants.MASTER_HOSTNAME);
    String masterPort = PROPERTIES.getProperty(Constants.MASTER_RPC_PORT);
    boolean useZk = Boolean.parseBoolean(PROPERTIES.getProperty(Constants.ZOOKEEPER_ENABLED));
    String masterAddress =
        (useZk ? Constants.HEADER_FT : Constants.HEADER) + masterHostname + ":" + masterPort;
    PROPERTIES.setProperty(Constants.MASTER_ADDRESS, masterAddress);
    checkUserFileBufferBytes();

    // Make sure the user hasn't set worker ports when there may be multiple workers per host
    int maxWorkersPerHost = getInt(Constants.INTEGRATION_YARN_WORKERS_PER_HOST_MAX);
    if (maxWorkersPerHost > 1) {
      String message = "%s cannot be specified when allowing multiple workers per host with "
          + Constants.INTEGRATION_YARN_WORKERS_PER_HOST_MAX + "=" + maxWorkersPerHost;
      Preconditions.checkState(System.getProperty(Constants.WORKER_DATA_PORT) == null,
          String.format(message, Constants.WORKER_DATA_PORT));
      Preconditions.checkState(System.getProperty(Constants.WORKER_RPC_PORT) == null,
          String.format(message, Constants.WORKER_RPC_PORT));
      Preconditions.checkState(System.getProperty(Constants.WORKER_WEB_PORT) == null,
          String.format(message, Constants.WORKER_WEB_PORT));
      PROPERTIES.setProperty(Constants.WORKER_DATA_PORT, "0");
      PROPERTIES.setProperty(Constants.WORKER_RPC_PORT, "0");
      PROPERTIES.setProperty(Constants.WORKER_WEB_PORT, "0");
    }
  }

  /**
   * Merges the current configuration properties with another one. A property from the new
   * configuration wins if it also appears in the current configuration.
   *
   * @param alternateConf The source {@link Configuration} to be merged
   */
  public static void merge(Configuration alternateConf) {
    merge(alternateConf.toMap());
  }

  /**
   * Merges the current configuration properties with alternate properties. A property from the new
   * configuration wins if it also appears in the current configuration.
   *
   * @param properties The source {@link Properties} to be merged
   */
  public static void merge(Map<?, ?> properties) {
    if (properties != null) {
      // merge the system properties
      PROPERTIES.putAll(properties);
    }
    checkUserFileBufferBytes();
  }

  // Public accessor methods

  // TODO(binfan): this method should be hidden and only used during initialization and tests.

  /**
   * Sets the value for the appropriate key in the {@link Properties}.
   *
   * @param key the key to set
   * @param value the value for the key
   */
  public static void set(String key, String value) {
    Preconditions.checkArgument(key != null && value != null,
        String.format("the key value pair (%s, %s) cannot have null", key, value));
    PROPERTIES.put(key, value);
    checkUserFileBufferBytes();
  }

  /**
   * Gets the value for the given key in the {@link Properties}.
   *
   * @param key the key to get the value for
   * @return the value for the given key
   */
  public static String get(String key) {
    if (!PROPERTIES.containsKey(key)) {
      // if key is not found among the default properties
      throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
    }
    String raw = PROPERTIES.getProperty(key);
    return lookup(raw);
  }

  /**
   * Checks if the {@link Properties} contains the given key.
   *
   * @param key the key to check
   * @return true if the key is in the {@link Properties}, false otherwise
   */
  public static boolean containsKey(String key) {
    return PROPERTIES.containsKey(key);
  }

  /**
   * Gets the integer representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as an {@code int}
   */
  public static int getInt(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      try {
        return Integer.parseInt(lookup(rawValue));
      } catch (NumberFormatException e) {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_INTEGER.getMessage(key));
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the long representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code long}
   */
  public static long getLong(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      try {
        return Long.parseLong(lookup(rawValue));
      } catch (NumberFormatException e) {
        LOG.warn("Configuration cannot evaluate key {} as long.", key);
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the double representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code double}
   */
  public static double getDouble(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      try {
        return Double.parseDouble(lookup(rawValue));
      } catch (NumberFormatException e) {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_DOUBLE.getMessage(key));
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the float representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code float}
   */
  public static float getFloat(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      try {
        return Float.parseFloat(lookup(rawValue));
      } catch (NumberFormatException e) {
        LOG.warn("Configuration cannot evaluate key {} as float.", key);
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the boolean representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code boolean}
   */
  public static boolean getBoolean(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      return Boolean.parseBoolean(lookup(rawValue));
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the value for the given key as a list.
   *
   * @param key the key to get the value for
   * @param delimiter the delimiter to split the values
   * @return the list of values for the given key
   */
  public static List<String> getList(String key, String delimiter) {
    Preconditions.checkArgument(delimiter != null, "Illegal separator for Alluxio properties as "
        + "list");
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      return Lists.newLinkedList(Splitter.on(delimiter).trimResults().omitEmptyStrings()
          .split(rawValue));
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the value for the given key as an enum value.
   *
   * @param key the key to get the value for
   * @param enumType the type of the enum
   * @param <T> the type of the enum
   * @return the value for the given key as an enum value
   */
  public static <T extends Enum<T>> T getEnum(String key, Class<T> enumType) {
    if (!PROPERTIES.containsKey(key)) {
      throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
    }
    final String val = get(key);
    return Enum.valueOf(enumType, val);
  }

  /**
   * Gets the bytes of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the bytes of the value for the given key
   */
  public static long getBytes(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = get(key);
      try {
        return FormatUtils.parseSpaceSize(rawValue);
      } catch (Exception ex) {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_BYTES.getMessage(key));
      }
    }
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the value for the given key as a class.
   *
   * @param key the key to get the value for
   * @param <T> the type of the class
   * @return the value for the given key as a class
   */
  @SuppressWarnings("unchecked")
  public static <T> Class<T> getClass(String key) {
    if (PROPERTIES.containsKey(key)) {
      String rawValue = PROPERTIES.getProperty(key);
      try {
        return (Class<T>) Class.forName(rawValue);
      } catch (Exception e) {
        String msg = "requested class could not be loaded";
        LOG.error("{} : {} , {}", msg, rawValue, e);
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * @return a copy of the internal {@link Properties} of as an immutable map
   */
  public static ImmutableMap<String, String> toMap() {
    return Maps.fromProperties(PROPERTIES);
  }

  /**
   * Lookup key names to handle ${key} stuff. Set as package private for testing.
   *
   * @param base string to look for
   * @return the key name with the ${key} substituted
   */
  private static String lookup(String base) {
    return lookupRecursively(base, new HashMap<String, String>());
  }

  /**
   * Actual recursive lookup replacement.
   *
   * @param base the String to look for
   * @param found {@link Map} of String that already seen in this path
   * @return resolved String value
   */
  private static String lookupRecursively(final String base, Map<String, String> found) {
    // check argument
    if (base == null) {
      return null;
    }

    String resolved = base;
    // Lets find pattern match to ${key}.
    // TODO(hsaputra): Consider using Apache Commons StrSubstitutor.
    Matcher matcher = CONF_REGEX.matcher(base);
    while (matcher.find()) {
      String match = matcher.group(2).trim();
      String value;
      if (!found.containsKey(match)) {
        value = lookupRecursively(PROPERTIES.getProperty(match), found);
        found.put(match, value);
      } else {
        value = found.get(match);
      }
      if (value != null) {
        LOG.debug("Replacing {} with {}", matcher.group(1), value);
        resolved = resolved.replaceFirst(REGEX_STRING, Matcher.quoteReplacement(value));
      }
    }
    return resolved;
  }

  /**
   * {@link Constants#USER_FILE_BUFFER_BYTES} should not bigger than {@link Integer#MAX_VALUE}
   * bytes.
   *
   * @throws IllegalArgumentException if USER_FILE_BUFFER_BYTES bigger than Integer.MAX_VALUE
   */
  private static void checkUserFileBufferBytes() {
    if (!containsKey(Constants.USER_FILE_BUFFER_BYTES)) { // load from hadoop conf
      return;
    }
    long usrFileBufferBytes = getBytes(Constants.USER_FILE_BUFFER_BYTES);
    Preconditions.checkArgument((usrFileBufferBytes & Integer.MAX_VALUE) == usrFileBufferBytes,
        "Invalid \"" + Constants.USER_FILE_BUFFER_BYTES + "\": " + usrFileBufferBytes);
  }

  private Configuration() {} // prevent instantiation
}
