package iua.edu.sentria.model.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import iua.edu.sentria.model.InstruccionPreconsulta;

public interface InstruccionPreconsultaRepository extends JpaRepository<InstruccionPreconsulta, Long> {

    List<InstruccionPreconsulta> findByEspecialidadIdAndActivoTrueOrderByOrdenAsc(Long especialidadId);

    // Recomendaciones generales de admisión
    List<InstruccionPreconsulta> findByEspecialidadIdIsNullAndActivoTrueOrderByOrdenAsc();
}
