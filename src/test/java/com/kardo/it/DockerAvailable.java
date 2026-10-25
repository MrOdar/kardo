package com.kardo.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Salta la prueba cuando no hay Docker en la maquina.
 *
 * <p>Sin esto, {@code mvn verify} fallaria en un equipo sin Docker y dejaria de
 * ser un comando seguro para cualquiera que clone el proyecto. En CI, donde
 * Docker existe, las pruebas se ejecutan de verdad: la falta de Docker debe
 * notarse como pruebas SALTADAS, nunca como pruebas en verde. Por eso el
 * workflow comprueba `docker info` antes de lanzar el build.
 */
// @Inherited es imprescindible: la condicion se declara en la clase base y las
// pruebas la heredan. Sin esto, JUnit no la ve en las subclases, intenta
// levantar el contexto y el build falla en una maquina sin Docker.
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Inherited
@EnabledIf(value = "com.kardo.it.DockerEnvironment#isPresent",
           disabledReason = "Docker no esta disponible: se omiten las pruebas de integracion")
public @interface DockerAvailable {
}
