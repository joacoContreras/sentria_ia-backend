package iua.edu.sentria.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import iua.edu.sentria.business.IAsistenteBusiness;
import iua.edu.sentria.controllers.AsistenteRestController;
import iua.edu.sentria.util.StandartResponseBusiness;

/** El frontend Next.js (local y Vercel) tiene que poder llamar a la API desde el navegador. */
@WebMvcTest(AsistenteRestController.class)
@Import({ CorsConfig.class, StandartResponseBusiness.class })
class CorsConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IAsistenteBusiness asistenteBusiness;

    @ParameterizedTest
    @ValueSource(strings = { "http://localhost:3000", "https://sentria-ia-frontend.vercel.app" })
    void preflightDelFrontendPermitido(String origen) throws Exception {
        mvc.perform(options("/api/v1/ia/chat")
                .header("Origin", origen)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origen));
    }

    @Test
    void origenDesconocidoRechazado() throws Exception {
        mvc.perform(options("/api/v1/ia/chat")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

}
