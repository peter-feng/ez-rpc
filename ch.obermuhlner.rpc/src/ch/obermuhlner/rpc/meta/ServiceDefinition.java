package ch.obermuhlner.rpc.meta;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;

public class ServiceDefinition {

	@XmlAttribute
	public String name;
	
	@XmlAttribute
	public String javaTypeName;

	@XmlElement(name = "method")
	public List<MethodDefinition> methodDefinitions = new ArrayList<>();
	
	@SuppressWarnings("unused")
	private ServiceDefinition() {
		// for Jaxb
	}
	
	public ServiceDefinition(String name, String javaTypeName) {
		this.name = name;
		this.javaTypeName = javaTypeName;
	}

	@Override
	public String toString() {
		return "StructDefinition [name=" + name + ", javaTypeName=" + javaTypeName + ", methodDefinitions=" + methodDefinitions + "]";
	}
}