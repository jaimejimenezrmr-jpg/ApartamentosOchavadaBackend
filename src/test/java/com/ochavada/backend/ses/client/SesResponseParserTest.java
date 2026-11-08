package com.ochavada.backend.ses.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Respuestas tomadas de los anexos de la especificación v3.1.2 (con sus erratas originales). */
class SesResponseParserTest {

    private final SesResponseParser parser = new SesResponseParser();

    @Test
    void parsesRegistrationOk() {
        SesResponse r = parser.parse("""
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                   <SOAP-ENV:Header/>
                   <SOAP-ENV:Body>
                      <ns3:comunicacionResponse xmlns:ns3="http://www.soap.servicios.hospedajes.mir.es/comunicacion">
                         <respuesta>
                            <codigoRetorno>0</codigoRetorno>
                            <descripcion>Ok</descripcion>
                            <lote>00000000-0000-0000-0000-000000000000</lote>
                         </respuesta>
                      </ns3:comunicacionResponse>
                   </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>""");

        assertThat(r.isOk()).isTrue();
        assertThat(r.batchId()).isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void parsesHeaderError() {
        SesResponse r = parser.parse("""
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Body>
                <ns3:comunicacionResponse xmlns:ns3="http://www.soap.servicios.hospedajes.mir.es/comunicacion">
                <respuesta><codigoRetorno>109</codigoRetorno><descripcion>Formato de solicitud incorrecto.</descripcion></respuesta>
                </ns3:comunicacionResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>""");

        assertThat(r.isOk()).isFalse();
        assertThat(r.code()).isEqualTo(109);
        assertThat(r.description()).startsWith("Formato");
    }

    @Test
    void parsesBatchQueryWithMisspelledElements() {
        SesResponse r = parser.parse("""
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Body>
                <ns3:comunicacionResponse xmlns:ns3="http://www.soap.servicios.hospedajes.mir.es/comunicacion">
                   <respuesta><codigo>0</codigo><descripcion>Ok</descripcion></respuesta>
                   <resultado>
                      <lote>00000000-0000-0000-0000-000000000000</lote>
                      <tipoComuniacion>PV</tipoComuniacion>
                      <tipoOperacion>A</tipoOperacion>
                      <codigoEstado>6</codigoEstado>
                      <descEstado>Se ha ejecutado pero hay errores en algunas comunicaciones</descEstado>
                      <resultadoComunicaciones>
                         <resutadoComunicacion>
                            <orden>1</orden>
                            <tipoError>Error validación de datos</tipoError>
                            <error>No existe un establecimiento para ese código.</error>
                         </resutadoComunicacion>
                      </resultadoComunicaciones>
                   </resultado>
                </ns3:comunicacionResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>""");

        assertThat(r.isOk()).isTrue();
        assertThat(r.batches()).hasSize(1);
        SesResponse.BatchResult batch = r.batches().getFirst();
        assertThat(batch.stateCode()).isEqualTo(6);
        assertThat(batch.isFinished()).isTrue();
        assertThat(batch.communications()).singleElement().satisfies(c -> {
            assertThat(c.order()).isEqualTo(1);
            assertThat(c.communicationCode()).isNull();
            assertThat(c.error()).contains("establecimiento");
        });
    }

    @Test
    void rejectsSoapFaultsAndExternalEntities() {
        assertThatThrownBy(() -> parser.parse("""
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
                <faultcode>soap:Server</faultcode><faultstring>Error interno</faultstring></soap:Fault></soap:Body></soap:Envelope>"""))
                .isInstanceOf(SesTransportException.class).hasMessageContaining("Error interno");

        assertThatThrownBy(() -> parser.parse("""
                <?xml version="1.0"?><!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <respuesta><codigo>&xxe;</codigo></respuesta>"""))
                .isInstanceOf(SesTransportException.class);
    }
}
