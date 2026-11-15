package com.kardo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contrato navegable de la API.
 *
 * <p><b>Para que.</b> Sin esto, el backend es una caja negra: para probar un
 * endpoint hay que leerse el controlador, montar el cuerpo a mano y buscarse un
 * token. Aqui se entra, se pulsa <em>Authorize</em>, se pega el token del login
 * y se prueba. La pagina sale de los propios controladores y de las anotaciones
 * de validacion, de modo que no hay nada que mantener al dia aparte del codigo.
 *
 * <p><b>El esquema de seguridad importa mas que la descripcion.</b> Declarar el
 * <em>bearer</em> es lo que convierte el visor en algo usable: sin el, cada
 * prueba responde 401 y parece que la API esta rota.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI kardoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kardo")
                        .version("v1")
                        .description("""
                                Control de inventario por lote para minimarkets: salida FEFO, \
                                escalera de rebajas con piso en el costo y circuito de mermas.

                                **Como probar:** abre sesion en `POST /api/v1/auth/login` con \
                                `demo@kardo.pe` / `kardo-demo-2026`, copia el `token` de la \
                                respuesta y pegalo en **Authorize**. La tienda sobre la que \
                                operas viaja firmada dentro de ese token: ningun endpoint acepta \
                                un `storeId` del cliente para decidir donde escribe.""")
                        .license(new License().name("MIT").url("https://opensource.org/license/mit")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token devuelto por /api/v1/auth/login o /api/v1/auth/register.")));
    }
}
