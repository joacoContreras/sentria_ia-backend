package iua.edu.sentria;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SentriaApplication {

	// PgJDBC manda la zona de la JVM al abrir cada conexión. En Windows suele
	// ser el alias "America/Buenos_Aires", que PostgreSQL 17 rechaza; se fija
	// la misma zona que usa sentria.zona_institucional().
	public static final String ZONA_INSTITUCIONAL = "America/Argentina/Cordoba";

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone(ZONA_INSTITUCIONAL));
		SpringApplication.run(SentriaApplication.class, args);
	}

}
