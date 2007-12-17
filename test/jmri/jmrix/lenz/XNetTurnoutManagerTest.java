// XNetTurnoutManagerTest.java

package jmri.jmrix.lenz;

import jmri.Turnout;
import jmri.TurnoutAddress;
import jmri.TurnoutManager;
import java.util.ArrayList;
import java.util.List;
import junit.framework.Assert;
import junit.framework.Test;
import junit.framework.TestSuite;

/**
 Tests for the jmri.jmrix.lenz.XNetTurnoutManager class.
 * @author			Bob Jacobsen Copyright 2004
 * @version         $Revision: 2.3 $
 */
public class XNetTurnoutManagerTest extends jmri.AbstractTurnoutMgrTest  {

    public String getSystemName(int i) {
        return "XT"+i;
    }

    XNetInterfaceScaffold lnis;

    public void testArraySort() {
        String[] str = new String[]{"8567", "8456"};
        jmri.util.StringUtil.sort(str);
        Assert.assertEquals("first ","8456",str[0]);
    }

    public void testMisses() {
        // sample address object
        TurnoutAddress a = new TurnoutAddress("XT22", "user");

        // try to get nonexistant turnouts
        Assert.assertTrue(null == l.getByUserName("foo"));
        Assert.assertTrue(null == l.getBySystemName("bar"));
    }

    public void testXNetMessages() {
        // send messages for 21, 22
        // notify that somebody else changed it...
        XNetReply m1 = new XNetReply();
        m1.setElement(0, 0x42);
        m1.setElement(1, 0x05);
        m1.setElement(2, 0x02);
        m1.setElement(3, 0x45);
        lnis.sendTestMessage(m1);

        // notify that somebody else changed it...
        XNetReply m2 = new XNetReply();
        m2.setElement(0, 0x42);
        m2.setElement(1, 0x05);
        m2.setElement(2, 0x04);
        m2.setElement(3, 0x43);
        lnis.sendTestMessage(m2);


        // try to get turnouts to see if they exist
        Assert.assertTrue(null != l.getBySystemName("XT21"));
        Assert.assertTrue(null != l.getBySystemName("XT22"));

        // check the list
        List testList = new ArrayList(2);
        testList.add("XT21");
        testList.add("XT22");
        Assert.assertEquals("system name list", testList, l.getSystemNameList());
    }

    public void testAsAbstractFactory () {
        // create and register the manager object
        XNetTurnoutManager l = new XNetTurnoutManager();
        jmri.InstanceManager.setTurnoutManager(l);

        // ask for a Turnout, and check type
        TurnoutManager t = jmri.InstanceManager.turnoutManagerInstance();

        Turnout o = t.newTurnout("XT21", "my name");


        if (log.isDebugEnabled()) log.debug("received turnout value "+o);
        Assert.assertTrue( null != (XNetTurnout)o);

        // make sure loaded into tables
        if (log.isDebugEnabled()) log.debug("by system name: "+t.getBySystemName("XT21"));
        if (log.isDebugEnabled()) log.debug("by user name:   "+t.getByUserName("my name"));

        Assert.assertTrue(null != t.getBySystemName("XT21"));
        Assert.assertTrue(null != t.getByUserName("my name"));

    }


    // from here down is testing infrastructure

    public XNetTurnoutManagerTest(String s) {
        super(s);
    }

    // Main entry point
    static public void main(String[] args) {
        String[] testCaseName = {"-noloading", XNetTurnoutManagerTest.class.getName()};
        junit.swingui.TestRunner.main(testCaseName);
    }

    // test suite from all defined tests
    public static Test suite() {
        TestSuite suite = new TestSuite(XNetTurnoutManagerTest.class);
        return suite;
    }

    // The minimal setup for log4J
    protected void setUp() { 
        apps.tests.Log4JFixture.setUp(); 
        // prepare an interface, register
        lnis = new XNetInterfaceScaffold(new LenzCommandStation());
        // create and register the manager object
        l = new XNetTurnoutManager();
        jmri.InstanceManager.setTurnoutManager(l);
    }
    protected void tearDown() { apps.tests.Log4JFixture.tearDown(); }

    static org.apache.log4j.Category log = org.apache.log4j.Category.getInstance(XNetTurnoutManagerTest.class.getName());

}
