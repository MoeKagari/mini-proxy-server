package server;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class ServerConfig {
	private IntSupplier listenPort;
	private BooleanSupplier useProxy;
	private Supplier<String> proxyHost;
	private IntSupplier proxyPort;
	private LongSupplier proxyTimeOut = () -> 60000;

	public ServerConfig(IntSupplier listenPort, BooleanSupplier useProxy, Supplier<String> proxyHost, IntSupplier proxyPort) {
		this.listenPort = listenPort;
		this.useProxy = useProxy;
		this.proxyHost = proxyHost == null ? () -> "127.0.0.1" : proxyHost;
		this.proxyPort = proxyPort;
	}

	public int getListenPort() {
		return this.listenPort.getAsInt();
	}

	public void setListenPort(IntSupplier listenPort) {
		this.listenPort = listenPort;
	}

	public boolean isUseProxy() {
		return this.useProxy.getAsBoolean();
	}

	public void setUseProxy(BooleanSupplier useProxy) {
		this.useProxy = useProxy;
	}

	public String getProxyHost() {
		return this.proxyHost.get();
	}

	public void setProxyHost(Supplier<String> proxyHost) {
		this.proxyHost = proxyHost;
	}

	public int getProxyPort() {
		return this.proxyPort.getAsInt();
	}

	public void setProxyPort(IntSupplier proxyPort) {
		this.proxyPort = proxyPort;
	}

	public long getProxyTimeOut() {
		return this.proxyTimeOut.getAsLong();
	}

	public void setProxyTimeOut(LongSupplier proxyTimeOut) {
		this.proxyTimeOut = proxyTimeOut;
	}
}
