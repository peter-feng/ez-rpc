package ch.obermuhlner.rpc.annotation;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({PARAMETER})
@Retention(RUNTIME)
public @interface RpcParameter {

	String name() default "";
	
}
