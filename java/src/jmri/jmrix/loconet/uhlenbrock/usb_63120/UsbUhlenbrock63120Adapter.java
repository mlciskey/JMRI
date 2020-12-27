package jmri.jmrix.loconet.uhlenbrock.usb_63120;

import jmri.jmrix.loconet.LnCommandStationType;
import jmri.jmrix.loconet.locobuffer.LocoBufferAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import purejavacomm.SerialPort;
import purejavacomm.UnsupportedCommOperationException;

import java.util.Arrays;

/**
 * Extend the code in jmri.jmrix.loconet.locobuffer so that it operates
 * correctly with the Uhlenbrock USB-adapter part no 63120.
 * Status: EXPERIMENTAL - as added to title until confirmed by hardware users in 4.21.4
 *
 * Specs:
 * PC - LocoNet Communication (from Uhlenbrock 63120 datasheet)
 * Communication between PC and LocoNet must be according to the following schema:
 * - Send message over USB and then wait to receive the sent message again, before a new message is sent.
 *   Process other messages received during the waiting period.
 * - LACK (Long Acknowledge Message) treatment: If a message can be followed by a LACK (see LocoNet
 *   documentation, for messages which can be followed by a LACK). A flag must be set by COM Port after
 *   the send and receive procedure. If this flag is set and the next message received is a LACK message then it must be processed because it is a response to the sent message. If the next message received is not a LACK then the set flag is reset. This ensures that a LACK is not assigned to a wrong message.
 * - Evaluate and process Received messages.
 *
 * @author Egbert Broerse Copyright (C) 2020
 */
public class UsbUhlenbrock63120Adapter extends LocoBufferAdapter {

    public UsbUhlenbrock63120Adapter() {
        super();

        validSpeeds = new String[]{Bundle.getMessage("Baud19200"), Bundle.getMessage("Baud38400"),
                Bundle.getMessage("Baud57600"), Bundle.getMessage("Baud115200")};
        validSpeedValues = new int[]{19200, 38400, 57600, 115200};
        configureBaudRate(validSpeeds[3]); // Set the default baud rate (localized)
        setCommandStationType(LnCommandStationType.getByName(LnCommandStationType.COMMAND_STATION_IBX_TYPE_1.getName()));
        // start off with Uhlenbrock IB--I CS product selected
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] validBaudRates() {
        return Arrays.copyOf(validSpeeds, validSpeeds.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int[] validBaudNumbers() {
        return Arrays.copyOf(validSpeedValues, validSpeedValues.length);
    }

    @Override
    public int defaultBaudIndex() {
        return 3;
    }

    /**
     * Local method to do specific configuration, overridden in class.
     */
    @Override
    protected void setSerialPort(SerialPort activeSerialPort) throws UnsupportedCommOperationException {
        // find the baud rate value, configure comm options
        int baud = currentBaudNumber(mBaudRate);
        activeSerialPort.setSerialPortParams(baud, SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

        // find and configure flow control from option
        int flow = SerialPort.FLOWCONTROL_RTSCTS_OUT; // default, but also defaults in selectedOption1
        if (getOptionState(option1Name).equals(validOption1[1])) {
            flow = SerialPort.FLOWCONTROL_NONE;
        }
        configureLeadsAndFlowControl(activeSerialPort, flow);

        log.info("Uhlenbrock adapter {}, mode={} RTSCTS_OUT={} RTSCTS_IN={}",
                (activeSerialPort.getFlowControlMode() == SerialPort.FLOWCONTROL_RTSCTS_OUT ? "set hardware flow control" : "set no flow control"),
                activeSerialPort.getFlowControlMode(),
                SerialPort.FLOWCONTROL_RTSCTS_OUT,
                SerialPort.FLOWCONTROL_RTSCTS_IN);
    }

    private final static Logger log = LoggerFactory.getLogger(UsbUhlenbrock63120Adapter.class);

}
