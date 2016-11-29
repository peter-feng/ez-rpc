package ch.obermuhlner.rpc.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ch.obermuhlner.rpc.RpcServiceException;
import ch.obermuhlner.rpc.meta.MetaDataService;
import ch.obermuhlner.rpc.transport.ClientTransport;
import ch.obermuhlner.rpc.transport.ServerTransport;

public class ServiceFactory {

	private static final String ASYNC_SUFFIX = "Async";
	
	private final MetaDataService metaDataService;

	public ServiceFactory(MetaDataService metaDataService) {
		this.metaDataService = metaDataService;
	}
	
	public <Service, AsyncService, ServiceImpl extends Service> Service createLocalService(Class<Service> serviceType, Class<AsyncService> asyncServiceType, ServiceImpl serviceImpl) {
		return createLocalService(serviceType, asyncServiceType, serviceImpl, () -> null, (session) -> {});
	}
	
	public <Service, AsyncService, ServiceImpl extends Service, Session> Service createLocalService(Class<Service> serviceType, Class<AsyncService> asyncServiceType, ServiceImpl serviceImpl, Supplier<Session> sessionSupplier, Consumer<Session> sessionConsumer) {
		Object proxyObject = Proxy.newProxyInstance(
				serviceType.getClassLoader(),
				new Class<?>[] { serviceType, asyncServiceType },
				(Object proxy, Method method, Object[] args) -> {
					try {
						if (method.getReturnType() == CompletableFuture.class || method.getReturnType() == Future.class) { 
							return CompletableFuture.supplyAsync(() -> {
								String syncMethodName = withoutAsyncSuffix(method.getName());
								Method implMethod;
								try {
									sessionConsumer.accept(sessionSupplier.get());
									implMethod = serviceImpl.getClass().getMethod(syncMethodName, method.getParameterTypes());
									return implMethod.invoke(serviceImpl, args);
								} catch (Exception e) {
									throw new RpcServiceException(e);
								}
							});
						} else {
							Method implMethod = serviceImpl.getClass().getMethod(method.getName(), method.getParameterTypes());
							return implMethod.invoke(serviceImpl, args);
						}
					} catch (InvocationTargetException e) {
						throw e.getTargetException();
					}
				});
		
		@SuppressWarnings("unchecked")
		Service proxyService = (Service) proxyObject;
		
		return proxyService;
	}

	public <Service, AsyncService> Service createRemoteService(Class<Service> serviceType, Class<AsyncService> asyncServiceType, ClientTransport clientTransport) {
		return createRemoteService(serviceType, asyncServiceType, clientTransport, () -> null);
	}
	
	public <Service, AsyncService, Session> Service createRemoteService(Class<Service> serviceType, Class<AsyncService> asyncServiceType, ClientTransport clientTransport, Supplier<Session> sessionSupplier) {
		Object proxyObject = Proxy.newProxyInstance(
				serviceType.getClassLoader(),
				new Class<?>[] { serviceType, asyncServiceType },
				(Object proxy, Method method, Object[] args) -> {
					try {
						boolean async = method.getReturnType() == CompletableFuture.class || method.getReturnType() == Future.class;
						
						String serviceName = serviceType.getName();
						String methodName = async ? withoutAsyncSuffix(method.getName()) : method.getName();
	
						Request request = new Request();
						request.serviceName = serviceName;
						request.methodName = methodName;
						request.arguments = metaDataService.createDynamicStruct(method, args);
						request.session = sessionSupplier.get();
						CompletableFuture<Object> future = clientTransport.send(request)
								.thenApply(response -> {
									if (response.exception != null) {
										throwAsException(metaDataService.adaptRemoteToLocal(response.exception));
									}
									return response.result.getField("result");
								});
						if (async) {
							return future;
						} else {
							return future.get();
						}
					} catch (ExecutionException e) {
						throw e.getCause();
					}
				});
		
		@SuppressWarnings("unchecked")
		Service proxyService = (Service) proxyObject;
		
		return proxyService;
	}

	private void throwAsException(Object exception) {
		if (exception == null) {
			return;
		}
		
		if (exception instanceof RuntimeException) {
			throw (RuntimeException) exception;
		}
		
		if (exception instanceof Error) {
			throw (Error) exception;
		}
		
		if (exception instanceof Exception) {
			throw new RpcServiceException((Exception) exception);
		}
	}

	public <Service, ServiceImpl extends Service> void publishService(Class<Service> serviceType, ServiceImpl serviceImpl, ServerTransport serverTransport) {
		publishService(serviceType, serviceImpl, serverTransport, (session) -> {});
	}
	
	public <Service, ServiceImpl extends Service, Session> void publishService(Class<Service> serviceType, ServiceImpl serviceImpl, ServerTransport serverTransport, Consumer<Session> sessionConsumer) {
		serverTransport.register(serviceType, serviceImpl, sessionConsumer);
	}

	private String withoutAsyncSuffix(String name) {
		if (name.endsWith(ASYNC_SUFFIX)) {
			return name.substring(0, name.length() - ASYNC_SUFFIX.length());
		}
		
		return name;
	}
}
