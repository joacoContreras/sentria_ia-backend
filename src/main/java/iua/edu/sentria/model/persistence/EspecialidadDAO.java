package iua.edu.sentria.model.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Lista blanca de especialidades para el triage (RF-06): el modelo solo puede sugerir una de estas.
@Repository
public class EspecialidadDAO {

    private final JdbcClient jdbc;

    public EspecialidadDAO(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> activas() {
        return jdbc.sql("SELECT nombre FROM sentria.especialidad WHERE activo ORDER BY nombre")
                .query(String.class).list();
    }
}
