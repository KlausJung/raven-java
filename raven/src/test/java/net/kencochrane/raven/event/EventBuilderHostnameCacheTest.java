package net.kencochrane.raven.event;

import mockit.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import static mockit.Deencapsulation.getField;
import static mockit.Deencapsulation.setField;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@Test(singleThreaded = true)
public class EventBuilderHostnameCacheTest {
    @Injectable
    private InetAddress mockLocalHost;
    @Injectable("serverName")
    private String mockLocalHostName;
    @Injectable
    private InetAddress mockTimingOutLocalHost;

    private static void resetHostnameCache() {
        setField(getHostnameCache(), "expirationTimestamp", 0l);
        setField(getHostnameCache(), "hostname", EventBuilder.DEFAULT_HOSTNAME);
    }

    private static Object getHostnameCache() {
        return getField(EventBuilder.class, "HOSTNAME_CACHE");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        new NonStrictExpectations() {{
            mockLocalHost.getCanonicalHostName();
            result = mockLocalHostName;

            mockTimingOutLocalHost.getCanonicalHostName();
            result = new Delegate() {
                public String getCanonicalHostName() throws Exception {
                    synchronized (EventBuilderHostnameCacheTest.this) {
                        EventBuilderHostnameCacheTest.this.wait();
                    }
                    return "";
                }
            };
        }};
        // Clean Hostname Cache
        resetHostnameCache();
    }

    @Test
    public void successfulHostnameRetrievalIsCachedForFiveHours(@Mocked("currentTimeMillis") final System system)
            throws Exception {
        new NonStrictExpectations(InetAddress.class) {{
            System.currentTimeMillis();
            result = 1L;
            InetAddress.getLocalHost();
            result = mockLocalHost;
        }};

        new EventBuilder().build();
        final long expirationTime = Deencapsulation.<Long>getField(getHostnameCache(), "expirationTimestamp");

        assertThat(expirationTime, is(TimeUnit.HOURS.toMillis(5) + System.currentTimeMillis()));
    }

    @Test(timeOut = 5000)
    public void unsuccessfulHostnameRetrievalIsCachedForOneSecond(
            @Mocked("currentTimeMillis") final System system)
            throws Exception {
        new NonStrictExpectations(InetAddress.class) {{
            System.currentTimeMillis();
            result = 1L;
            InetAddress.getLocalHost();
            result = mockTimingOutLocalHost;
        }};

        new EventBuilder().build();
        unlockTimingOutLocalHost();
        final long expirationTime = Deencapsulation.<Long>getField(getHostnameCache(), "expirationTimestamp");

        assertThat(expirationTime, is(TimeUnit.SECONDS.toMillis(1) + System.currentTimeMillis()));
    }

    @Test(timeOut = 5000)
    public void unsuccessfulHostnameRetrievalUsesLastKnownCachedValue() throws Exception {
        new NonStrictExpectations(InetAddress.class) {{
            InetAddress.getLocalHost();
            result = mockLocalHost;
            result = mockTimingOutLocalHost;
        }};


        new EventBuilder().build();
        setField(getHostnameCache(), "expirationTimestamp", 0l);
        Event event = new EventBuilder().build();
        unlockTimingOutLocalHost();

        assertThat(event.getServerName(), is(mockLocalHostName));
        new Verifications() {{
            mockLocalHost.getCanonicalHostName();
            mockTimingOutLocalHost.getCanonicalHostName();
        }};
    }

    private void unlockTimingOutLocalHost() {
        synchronized (this) {
            this.notify();
        }
    }
}
