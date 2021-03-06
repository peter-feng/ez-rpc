package ch.obermuhlner.rpc.meta;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import ch.obermuhlner.rpc.annotation.RpcEnum;
import ch.obermuhlner.rpc.annotation.RpcEnumValue;
import ch.obermuhlner.rpc.annotation.RpcField;
import ch.obermuhlner.rpc.annotation.RpcMethod;
import ch.obermuhlner.rpc.annotation.RpcParameter;
import ch.obermuhlner.rpc.annotation.RpcService;
import ch.obermuhlner.rpc.annotation.RpcStruct;
import ch.obermuhlner.rpc.data.DynamicStruct;
import ch.obermuhlner.rpc.exception.RpcException;
import ch.obermuhlner.rpc.meta.adapter.Adapter;
import ch.obermuhlner.rpc.meta.adapter.bigdecimal.BigDecimalAdapter;
import ch.obermuhlner.rpc.meta.adapter.exception.IllegalArgumentExceptionAdapter;
import ch.obermuhlner.rpc.meta.adapter.time.DateAdapter;
import ch.obermuhlner.rpc.meta.adapter.time.LocalDateAdapter;
import ch.obermuhlner.rpc.meta.adapter.time.LocalDateTimeAdapter;
import ch.obermuhlner.rpc.meta.adapter.time.PeriodAdapter;

public class MetaDataService implements AutoCloseable {

	private File metaDataFile;

	private final MetaData metaData = new MetaData();
	
	private final List<Adapter<?, ?>> adapters = new ArrayList<Adapter<?, ?>>();

	public MetaDataService() {
		this(true);
	}

	public MetaDataService(boolean autoAdapters) {
		this(null, autoAdapters);
	}
	
	public MetaDataService(File metaDataFile) {
		this(metaDataFile, true);
	}
	
	public MetaDataService(File metaDataFile, boolean autoAdapters) {
		this.metaDataFile = metaDataFile;
		
		if (metaDataFile != null) {
			load(metaDataFile);
		}
		
		if (autoAdapters) {
			addAdapter(new BigDecimalAdapter());
			addAdapter(new DateAdapter());
			addAdapter(new LocalDateTimeAdapter());
			addAdapter(new LocalDateAdapter());
			addAdapter(new PeriodAdapter());
			addAdapter(new IllegalArgumentExceptionAdapter());
		}
	}
	
	@Override
	public void close() {
		if (metaDataFile != null) {
			save(metaDataFile);
		}
	}
	
	public void load(File file) {
		if (!file.exists()) {
			return;
		}
		
		MetaData loading = loadMetaData(file);
		
		for (EnumDefinition enumDefinition : loading.getEnumDefinitions().get()) {
			metaData.addEnumDefinition(enumDefinition);
		}

		for (StructDefinition structDefinition : loading.getStructDefinitions().get()) {
			metaData.addStructDefinition(structDefinition);
		}

		for (ServiceDefinition serviceDefinition : loading.getServiceDefinitions().get()) {
			metaData.addServiceDefinition(serviceDefinition);
		}
	}

	public void save(File file) {
		saveMetaData(metaData, file);
	}
	
	public MetaData getMetaData() {
		return metaData;
	}
	
	public void addAdapter(Adapter<?, ?> adapter) {
		adapters.add(adapter);
		
		registerStruct(adapter.getRemoteType());
	}

	public ServiceDefinition registerService(Class<?> type) {
		String name = type.getSimpleName();
		Class<?> sessionJavaClass = null;

		ServiceDefinition serviceDefinition = findServiceDefinitionByType(name);
		if (serviceDefinition != null) {
			return serviceDefinition;
		}

		RpcService annotation = type.getAnnotation(RpcService.class);
		if (annotation != null) {
			if (annotation.name() != null && !annotation.name().equals("")) {
				name = annotation.name();
			}
			if (annotation.session() != null && annotation.session() != Void.class) {
				sessionJavaClass = annotation.session();
			}
		}
		
		serviceDefinition = new ServiceDefinition();
		serviceDefinition.name = name;
		serviceDefinition.javaName = type.getName();
		serviceDefinition.sessionType = toTypeString(sessionJavaClass);

		fillServiceDefinition(serviceDefinition, type);

		metaData.addServiceDefinition(serviceDefinition);

		return serviceDefinition;
	}
	
	public StructDefinition registerStruct(Class<?> type) {
		if (type == DynamicStruct.class) {
			return null;
		}
		
		String name = type.getSimpleName();

		StructDefinition structDefinition = findStructDefinitionByType(type.getName());
		if (structDefinition != null) {
			return structDefinition;
		}

		RpcStruct annotation = type.getAnnotation(RpcStruct.class);
		if (annotation != null) {
			if (annotation.name() != null && !annotation.name().equals("")) {
				name = annotation.name();
			}
		}
		
		structDefinition = new StructDefinition();
		structDefinition.name = name;
		structDefinition.javaName = type.getName();

		metaData.addStructDefinition(structDefinition); // HACK - register incomplete to avoid recursive registration if type references itself
		fillStructureDefinition(structDefinition, type);

		metaData.addStructDefinition(structDefinition);

		return structDefinition;
	}
	
	public EnumDefinition registerEnum(Class<?> type) {
		String name = type.getSimpleName();
		
		EnumDefinition enumDefinition = findEnumDefinitionByType(type.getName());
		if (enumDefinition != null) {
			return enumDefinition;
		}

		RpcEnum annotation = type.getAnnotation(RpcEnum.class);
		if (annotation != null) {
			if (annotation.name() != null && !annotation.name().equals("")) {
				name = annotation.name();
			}
		}

		enumDefinition = new EnumDefinition();
		enumDefinition.name = name;
		enumDefinition.javaName = type.getName();
		
		fillEnumDefinition(enumDefinition, type);

		metaData.addEnumDefinition(enumDefinition);

		return enumDefinition;
	}

	private void fillEnumDefinition(EnumDefinition enumDefinition, Class<?> type) {
		@SuppressWarnings("unchecked")
		Class<Enum<?>> enumType = (Class<Enum<?>>) type;
		enumDefinition.values = new ArrayList<>();
		for (Enum<?> enumValue : enumType.getEnumConstants()) {
			EnumValueDefinition enumValueDefinition = new EnumValueDefinition();
			enumValueDefinition.name = enumValue.name();
			enumValueDefinition.id = null;
			try {
				Field field = enumType.getField(enumValue.name());
				RpcEnumValue annotation = field.getAnnotation(RpcEnumValue.class);
				if (annotation != null) {
					if (annotation.name() != null && !annotation.name().equals("")) {
						enumValueDefinition.name = annotation.name();
					}
					if (annotation.id() >= 0) {
						enumValueDefinition.id = annotation.id();
					}
				}
			} catch (NoSuchFieldException | SecurityException e) {
				throw new RpcException(e);
			}
			enumDefinition.values.add(enumValueDefinition);
		}
	}
	
	public static long generateId(String name) {
		long id = -Math.abs(name.hashCode());
		return id;
	}

	private void fillServiceDefinition(ServiceDefinition serviceDefinition, Class<?> type) {
		for (Method method : type.getMethods()) {
			MethodDefinition methodDefinition = toMethodDefinition(method);
			serviceDefinition.methodDefinitions.add(methodDefinition);
		}
	}
	
	private MethodDefinition toMethodDefinition(Method method) {
		MethodDefinition methodDefinition = new MethodDefinition();
		
		methodDefinition.name = method.getName();
		RpcMethod annotation = method.getAnnotation(RpcMethod.class);
		if (annotation != null) {
			if (annotation.name() != null && !annotation.name().equals("")) {
				methodDefinition.javaName = methodDefinition.name;
				methodDefinition.name = annotation.name();
			}			
		}
		
		methodDefinition.returns= toTypeString(method.getReturnType());
		
		for (Parameter parameter : method.getParameters()) {
			ParameterDefinition parameterDefinition = toParameterDefinition(parameter);
			methodDefinition.parameterDefinitions.add(parameterDefinition);
		}
		
		return methodDefinition;
	}

	private ParameterDefinition toParameterDefinition(Parameter parameter) {
		ParameterDefinition parameterDefinition = new ParameterDefinition();
		
		parameterDefinition.name = parameter.getName();
		RpcParameter annotation = parameter.getAnnotation(RpcParameter.class);
		if (annotation != null) {
			if (annotation.name() != null && !annotation.name().equals("")) {
				// do not use the original parameter.getName() as javaName, since it is something stupid like "arg0"
				parameterDefinition.name = annotation.name();
			}			
		}

		parameterDefinition.type = toTypeString(parameter.getType());
		
		return parameterDefinition;
	}

	private void fillStructureDefinition(StructDefinition structDefinition, Class<?> structClass) {
		for (Field field : structClass.getFields()) {
			FieldDefinition fieldDefinition = new FieldDefinition();
			fieldDefinition.name = field.getName();
			fieldDefinition.type = toTypeString(field.getType());
			fieldDefinition.element = toTypeString(field.getType().getComponentType());
			
			RpcField annotation = field.getAnnotation(RpcField.class);
			if (annotation != null) {
				if (annotation.name() != null && !annotation.name().equals("")) {
					fieldDefinition.javaName = fieldDefinition.name;
					fieldDefinition.name = annotation.name();
				}
				if (annotation.element() != null && annotation.element() != Void.class) {
					fieldDefinition.element = toTypeString(annotation.element());
				}
				if (annotation.key() != null && annotation.key() != Void.class) {
					fieldDefinition.key = toTypeString(annotation.key());
				}
				if (annotation.value() != null && annotation.value() != Void.class) {
					fieldDefinition.value = toTypeString(annotation.value());
				}
			}
			
			String neededType = null;
			if (Type.LIST.toTypeName().equals(fieldDefinition.type) && fieldDefinition.element == null) {
				neededType = "element";
			} else if (Type.SET.toTypeName().equals(fieldDefinition.type) && fieldDefinition.element == null) {
				neededType = "element";
			} else if (Type.MAP.toTypeName().equals(fieldDefinition.type) && fieldDefinition.key == null) {
				neededType = "key";
			} else if (Type.MAP.toTypeName().equals(fieldDefinition.type) && fieldDefinition.value == null) {
				neededType = "value";
			}
			
			if (neededType != null) {
				throw new RpcException("Field '" + structClass.getName() + "." + field.getName() + "' of type '" + fieldDefinition.type + "' must specify '" + neededType + "' type in @RpcField");
			}

			structDefinition.fieldDefinitions.add(fieldDefinition);
		}
	}

	private Type toType(Class<?> type) {
		if (type == null) {
			return null;
		}
		if (type == Boolean.class || type == boolean.class) {
			return Type.BOOL;
		}
		if (type == Integer.class || type == int.class) {
			return Type.INT;
		}
		if (type == Long.class || type == long.class) {
			return Type.LONG;
		}
		if (type == Double.class || type == double.class) {
			return Type.DOUBLE;
		}
		if (type == String.class) {
			return Type.STRING;
		}
		if (List.class.isAssignableFrom(type) || type == Object[].class) {
			return Type.LIST;
		}
		if (Set.class.isAssignableFrom(type)) {
			return Type.SET;
		}
		if (Map.class.isAssignableFrom(type)) {
			return Type.MAP;
		}

		if (type.getAnnotation(RpcStruct.class) != null) {
			return Type.STRUCT;
		}
		if (type.getAnnotation(RpcEnum.class) != null) {
			return Type.ENUM;
		}

		return null;
	}
	
	public String toTypeString(Class<?> javaClass) {
		@SuppressWarnings("rawtypes")
		Adapter adapter = findAdapterByLocalType(javaClass);
		if (adapter != null) {
			javaClass = adapter.getRemoteType();
		}

		Type type = toType(javaClass);
		if (type == null) {
			return null;
		}
		if (type == Type.STRUCT) {
			return registerStruct(javaClass).name;
		} else if (type == Type.ENUM) {
			return registerEnum(javaClass).name;
		} else {
			return type.toTypeName();
		}
	}
	
	public StructDefinition getStructDefinition(String name, ClassLoader classLoader) {
		StructDefinition structDefinition = findStructDefinitionByName(name);
		if (structDefinition == null) {
			try {
				
				Class<?> type = classLoader.loadClass(name);
				structDefinition = registerStruct(type);
			} catch (ClassNotFoundException e) {
				return null;
			}
		}
		
		return structDefinition;
	}
	
	public Class<?> getClass(Class<?> type, FieldDefinition fieldDefinition) {
		try {
			return type.getField(fieldDefinition.name).getType();
		} catch (NoSuchFieldException | SecurityException e) {
			throw new RpcException(e);
		}
	}
	
	public String toJavaSignature(FieldDefinition fieldDefinition) {
		Type type = findTypeByName(fieldDefinition.type);

		switch(type) {
			case LIST:
				return type.toJavaPrimitiveTypeName() + "<" + toJavaClassSignature(fieldDefinition.element) + ">";
			case SET:
				return type.toJavaPrimitiveTypeName() + "<" + toJavaClassSignature(fieldDefinition.element) + ">";
			case MAP:
				return type.toJavaPrimitiveTypeName() + "<" + toJavaClassSignature(fieldDefinition.key) 
						+ ", " + toJavaClassSignature(fieldDefinition.value) + ">";
			case ENUM:
			case STRUCT:
				EnumDefinition enumDefinition = findEnumDefinitionByName(fieldDefinition.type);
				if (enumDefinition != null) {
					return enumDefinition.javaName;
				}
				StructDefinition structDefinition = findStructDefinitionByName(fieldDefinition.type);
				if (structDefinition != null) {
					return structDefinition.javaName;
				}
				throw new RpcException("Unknown type: " + fieldDefinition);
			default:
				return type.toJavaPrimitiveTypeName();
		}
	}

	public String toJavaClassSignature(String typeName) {
		if (typeName == null) {
			return null;
		}
		
		Type type = findTypeByName(typeName);

		switch(type) {
			case STRUCT:
				return findStructDefinitionByName(typeName).javaName;
			default:
				return type.toJavaClassTypeName();
		}
	}

	public Type findTypeByName(String typeName) {
		if (typeName == null) {
			return null;
		}
		
		for (Type type : Type.values()) {
			if (type.toTypeName().equals(typeName)) {
				return type;
			}
		}
		
		// TODO how to recognize Type.ENUM?
		return Type.STRUCT;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Object adaptRemoteToLocal(Object remote) {
		if (remote == null) {
			return null;
		}
		
		Adapter adapter = findAdapterByRemoteType(remote.getClass());
		if (adapter != null) {
			return adapter.convertRemoteToLocal(remote);
		}
		return remote;
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Object adaptLocalToRemote(Object local) {
		if (local == null) {
			return null;
		}
		
		Adapter adapter = findAdapterByLocalType(local.getClass());
		if (adapter != null) {
			return adapter.convertLocalToRemote(local);
		}
		return local;
	}

	public Adapter<?, ?> findAdapterByLocalType(Class<?> localType) {
		return adapters.stream()
			.filter(adapter -> adapter.getLocalType() == localType)
			.findFirst()
			.orElse(null);
	}
	
	public Adapter<?, ?> findAdapterByRemoteType(Class<?> remoteType) {
		return adapters.stream()
			.filter(adapter -> adapter.getRemoteType() == remoteType)
			.findFirst()
			.orElse(null);
	}
	
	public FieldDefinition findFieldDefinition(String remoteTypeName, String fieldName) {
		StructDefinition structDefinition = findStructDefinitionByType(remoteTypeName);
		if (structDefinition == null) {
			return null;
		}
		return structDefinition.findFieldDefinition(fieldName);
	}
	
	private ServiceDefinition findServiceDefinitionByType(String javaTypeName) {
		return metaData.getServiceDefinitions().findByType(javaTypeName);
	}
	
	private StructDefinition findStructDefinitionByName(String name) {
		return metaData.getStructDefinitions().findByName(name);
	}
	
	private StructDefinition findStructDefinitionByType(String javaTypeName) {
		return metaData.getStructDefinitions().findByType(javaTypeName);
	}
	
	private EnumDefinition findEnumDefinitionByType(String javaTypeName) {
		return metaData.getEnumDefinitions().findByType(javaTypeName);
	}
	
	public EnumDefinition findEnumDefinitionByName(String name) {
		return metaData.getEnumDefinitions().findByName(name);
	}
	
	public static void saveMetaData(MetaData metaData, File file) {
		try {
			JAXBContext context = JAXBContext.newInstance(MetaData.class);
			Marshaller marshaller = context.createMarshaller();

			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
			
			marshaller.marshal(metaData, file);
		} catch (JAXBException e) {
			throw new RpcException(e);
		}
	}
	
	public static MetaData loadMetaData(File file) {
		try {
			JAXBContext context = JAXBContext.newInstance(MetaData.class);
			Unmarshaller unmarshaller = context.createUnmarshaller();

			return (MetaData) unmarshaller.unmarshal(file);
		} catch (JAXBException e) {
			throw new RpcException(e);
		}
	}

	public DynamicStruct createDynamicStruct(Method method, Object[] args) {
		DynamicStruct dynamicStruct = new DynamicStruct();
		
		// TODO use RpcMethod for name and convert type if necessary
		dynamicStruct.name = method.getName() + "_Request";
		
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			String parameterName = parameter.getName();
			Object parameterValue = args[i];
			
			// TODO use RpcParameter for name and convert type if necessary
			
			dynamicStruct.setField(parameterName, parameterValue);
		}
		
		return dynamicStruct;
	}

	public Object[] toArguments(Method method, DynamicStruct arguments) {
		Parameter[] parameters = method.getParameters();
		Object[] result = new Object[parameters.length];
		
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			String parameterName = parameter.getName();

			// TODO use RpcParameter for name

			Object parameterValue = arguments.getField(parameterName);
			result[i] = parameterValue;
		}
		
		return result;
	}
}
