package ch.obermuhlner.rpc.service;

import java.io.Serializable;

import ch.obermuhlner.rpc.annotation.RpcStruct;
import ch.obermuhlner.rpc.data.DynamicStruct;

@RpcStruct(name = "RpcRequest")
public class Request implements Serializable {

	private static final long serialVersionUID = 1L;
	
	public String serviceName;
	public String methodName;
	public boolean execute;
	public DynamicStruct arguments;
	public Object session;
	public String requestId;
	
	@Override
	public String toString() {
		return "Request [serviceName=" + serviceName + ", methodName=" + methodName + ", execute=" + execute
				+ ", arguments=" + arguments + ", session=" + session + ", requestId=" + requestId + "]";
	}
}
