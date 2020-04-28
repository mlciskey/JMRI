package jmri.jmris.srcp;

import jmri.util.JUnitUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the jmri.jmris.srcp.JmriSRCPSensorServer class
 *
 * @author Paul Bender Copyright (C) 2012,2016,2018
 */
public class JmriSRCPSensorServerTest extends jmri.jmris.AbstractSensorServerTestBase {

    private ByteArrayOutputStream output;

    /**
     * {@inhertDoc} 
     */
    @Override
    public void checkErrorStatusSent(){
         assertThat(output.toString()).endsWith("499 ERROR unspecified error\n\r").withFailMessage("Active Message Sent");
    }

    /**
     * {@inhertDoc} 
     */
    @Override
    public void checkSensorActiveSent(){
        assertThat(output.toString()).endsWith("100 INFO 0 FB 1 1\n\r").withFailMessage("Active Message Sent");
    }

    /**
     * {@inhertDoc} 
     */
    @Override
    public void checkSensorInActiveSent(){
        assertThat(output.toString()).endsWith("100 INFO 0 FB 1 0\n\r").withFailMessage("Active Message Sent");
    }

    /**
     * {@inhertDoc} 
     */
    @Override
    public void checkSensorUnknownSent(){
        assertThat(output.toString()).endsWith("411 ERROR unknown value\n\r").withFailMessage("Active Message Sent");

    }

    @BeforeEach
    @Override
    public void setUp() {
        JUnitUtil.setUp();

        jmri.util.JUnitUtil.initInternalSensorManager();
        output = new ByteArrayOutputStream();
        java.io.DataInputStream input = new java.io.DataInputStream(System.in);
        ss = new JmriSRCPSensorServer(input, output);
    }

    @AfterEach public void tearDown() throws Exception {
        ss.dispose();
        ss = null;
        JUnitUtil.tearDown();
    }

}
