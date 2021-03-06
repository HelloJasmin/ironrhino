package org.ironrhino.core.cache.impl;

import static org.ironrhino.core.metadata.Profiles.CLOUD;
import static org.ironrhino.core.metadata.Profiles.DUAL;

import java.io.EOFException;
import java.io.ObjectStreamConstants;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.ironrhino.core.cache.CacheManager;
import org.ironrhino.core.spring.configuration.PriorityQualifier;
import org.ironrhino.core.spring.configuration.ServiceImplementationConditional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@SuppressWarnings({ "unchecked", "rawtypes" })
@Component("cacheManager")
@ServiceImplementationConditional(profiles = { DUAL, CLOUD })
@Slf4j
public class RedisCacheManager implements CacheManager {

	@Autowired
	@PriorityQualifier
	private RedisTemplate cacheRedisTemplate;

	@Autowired
	@Qualifier("stringRedisTemplate")
	@PriorityQualifier
	private StringRedisTemplate cacheStringRedisTemplate;

	@PostConstruct
	public void init() {
		cacheRedisTemplate.setValueSerializer(new FallbackToStringSerializer());
	}

	@Override
	public void put(String key, Object value, int timeToLive, TimeUnit timeUnit, String namespace) {
		try {
			if (timeToLive > 0)
				cacheRedisTemplate.opsForValue().set(generateKey(key, namespace), value, timeToLive, timeUnit);
			else
				cacheRedisTemplate.opsForValue().set(generateKey(key, namespace), value);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public void putWithTti(String key, Object value, int timeToIdle, TimeUnit timeUnit, String namespace) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean exists(String key, String namespace) {
		if (key == null)
			return false;
		try {
			Boolean b = cacheRedisTemplate.hasKey(generateKey(key, namespace));
			return b != null && b;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return false;
		}
	}

	@Override
	public Object get(String key, String namespace) {
		if (key == null)
			return null;
		try {
			return cacheRedisTemplate.opsForValue().get(generateKey(key, namespace));
		} catch (SerializationFailedException e) {
			log.warn(e.getMessage(), e);
			delete(key, namespace);
			return null;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	@Override
	public Object getWithTti(String key, String namespace, int timeToIdle, TimeUnit timeUnit) {
		if (key == null)
			return null;
		String actualKey = generateKey(key, namespace);
		try {
			if (timeToIdle > 0)
				cacheRedisTemplate.expire(actualKey, timeToIdle, timeUnit);
			return cacheRedisTemplate.opsForValue().get(actualKey);
		} catch (SerializationFailedException e) {
			log.warn(e.getMessage(), e);
			delete(key, namespace);
			return null;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	@Override
	public long ttl(String key, String namespace) {
		if (key == null)
			return 0;
		String actualKey = generateKey(key, namespace);
		Long value = cacheRedisTemplate.getExpire(actualKey, TimeUnit.MILLISECONDS);
		if (value == null)
			value = 0L;
		if (value == -2)
			value = 0L; // not exists
		return value;
	}

	@Override
	public void setTtl(String key, String namespace, int timeToLive, TimeUnit timeUnit) {
		if (key == null)
			return;
		cacheRedisTemplate.expire(generateKey(key, namespace), timeToLive, timeUnit);
	}

	@Override
	public void delete(String key, String namespace) {
		if (StringUtils.isBlank(key))
			return;
		try {
			cacheRedisTemplate.delete(generateKey(key, namespace));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public void mput(Map<String, Object> map, final int timeToLive, TimeUnit timeUnit, String namespace) {
		if (map == null)
			return;
		try {
			Map<String, Object> temp = new HashMap<>();
			map.forEach((key, value) -> temp.put(generateKey(key, namespace), value));
			cacheRedisTemplate.opsForValue().multiSet(temp);
			temp.keySet().forEach(key -> cacheRedisTemplate.expire(key, timeToLive, timeUnit));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public Map<String, Object> mget(Set<String> keys, String namespace) {
		if (keys == null)
			return null;
		keys = keys.stream().filter(StringUtils::isNotBlank).collect(Collectors.toCollection(HashSet::new));
		try {
			List<Object> list = cacheRedisTemplate.opsForValue()
					.multiGet(keys.stream().map(key -> generateKey(key, namespace)).collect(Collectors.toList()));
			Map<String, Object> result = new HashMap<>();
			int i = 0;
			for (String key : keys) {
				result.put(key, list.get(i));
				i++;
			}
			return result;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	@Override
	public void mdelete(final Set<String> keys, final String namespace) {
		if (keys == null)
			return;
		try {
			cacheRedisTemplate.delete(keys.stream().filter(StringUtils::isNotBlank)
					.map(key -> generateKey(key, namespace)).collect(Collectors.toList()));
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}

	@Override
	public boolean putIfAbsent(String key, Object value, int timeToLive, TimeUnit timeUnit, String namespace) {
		String actualkey = generateKey(key, namespace);
		Boolean success = cacheRedisTemplate.opsForValue().setIfAbsent(actualkey, value);
		if (success == null)
			return false;
		if (success && timeToLive > 0)
			cacheRedisTemplate.expire(actualkey, timeToLive, timeUnit);
		return success;
	}

	@Override
	public long increment(String key, long delta, int timeToLive, TimeUnit timeUnit, String namespace) {
		String actualkey = generateKey(key, namespace);
		Long result = cacheRedisTemplate.opsForValue().increment(actualkey, delta);
		if (result == null)
			throw new RuntimeException("Unexpected null");
		if (timeToLive > 0)
			cacheRedisTemplate.expire(actualkey, timeToLive, timeUnit);
		return result;
	}

	private String generateKey(String key, String namespace) {
		if (StringUtils.isNotBlank(namespace)) {
			StringBuilder sb = new StringBuilder(namespace.length() + key.length() + 1);
			sb.append(namespace);
			sb.append(':');
			sb.append(key);
			return sb.toString();
		} else {
			return key;
		}

	}

	@Override
	public boolean supportsTti() {
		return false;
	}

	@Override
	public boolean supportsGetTtl() {
		return true;
	}

	@Override
	public boolean supportsUpdateTtl() {
		return true;
	}

	public void invalidate(String namespace) {
		RedisScript<Boolean> script = new DefaultRedisScript<>(
				"local keys = redis.call('keys', ARGV[1]) \n for i=1,#keys,5000 do \n redis.call('del', unpack(keys, i, math.min(i+4999, #keys))) \n end \n return true",
				Boolean.class);
		cacheStringRedisTemplate.execute(script, Collections.emptyList(), namespace + ":*");
	}

	private static class FallbackToStringSerializer extends JdkSerializationRedisSerializer {

		@Override
		public byte[] serialize(Object object) {
			if (object instanceof String)
				return ((String) object).getBytes(StandardCharsets.UTF_8);
			return super.serialize(object);
		}

		@Override
		public Object deserialize(byte[] bytes) {
			try {
				return super.deserialize(bytes);
			} catch (SerializationException se) {
				if (!isJavaSerialized(bytes) && se.getCause() instanceof SerializationFailedException) {
					Throwable cause = se.getCause().getCause();
					if ((cause instanceof StreamCorruptedException || cause instanceof EOFException)
							&& org.ironrhino.core.util.StringUtils.isUtf8(bytes))
						return new String(bytes, StandardCharsets.UTF_8);
				}
				throw se;
			}
		}

		private static boolean isJavaSerialized(byte[] bytes) {
			if (bytes.length > 2) {
				short magic = (short) ((bytes[1] & 0xFF) + (bytes[0] << 8));
				return magic == ObjectStreamConstants.STREAM_MAGIC;
			}
			return false;
		}
	}

}
