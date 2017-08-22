package server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ProxyConfiguration;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import tool.function.FunctionUtils;

@SuppressWarnings("serial")
public class ProxyServerServlet extends HttpServlet {
	private final ServerConfig config;
	private final Server server;

	public ProxyServerServlet(ServerConfig config) {
		this.config = config;
		this.server = new Server();
	}

	public void start() throws Exception {
		this.setConnector();

		ConnectHandler proxy = new ConnectHandler();
		this.server.setHandler(proxy);

		ServletContextHandler context = new ServletContextHandler(proxy, "/", ServletContextHandler.SESSIONS);
		ServletHolder holder = new ServletHolder(this);
		holder.setInitParameter("maxThreads", "256");
		holder.setInitParameter("timeout", "600000");
		context.addServlet(holder, "/*");

		this.server.start();
	}

	/** 默认不检查 config 配置是否改变 */
	public void restart() throws Exception {
		this.server.stop();
		this.setConnector();
		this.server.start();
	}

	public void end() throws Exception {
		this.server.stop();
		this.server.join();
	}

	private void setConnector() {
		ServerConnector connector = new ServerConnector(this.server);
		connector.setHost("127.0.0.1");
		connector.setPort(this.config.getListenPort());
		this.server.setConnectors(new Connector[] { connector });
	}

	public ServerConfig getConfig() {
		return this.config;
	}

	/*--------------------------------------------------------------------------------------------------*/

	private static final Set<String> HOP_HEADERS = new HashSet<>();
	static {
		HOP_HEADERS.add("proxy-connection");
		HOP_HEADERS.add("connection");
		HOP_HEADERS.add("keep-alive");
		HOP_HEADERS.add("transfer-encoding");
		HOP_HEADERS.add("te");
		HOP_HEADERS.add("trailer");
		HOP_HEADERS.add("proxy-authorization");
		HOP_HEADERS.add("proxy-authenticate");
		HOP_HEADERS.add("upgrade");
	}

	private HttpClient client;

	@Override
	public void destroy() {
		try {
			this.client.stop();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void init() throws ServletException {
		this.client = new HttpClient();
		if (this.config.isUseProxy()) {
			this.client.setProxyConfiguration(new ProxyConfiguration(this.config.getProxyHost(), this.config.getProxyPort()));
		}

		QueuedThreadPool executor = new QueuedThreadPool(256);
		String servletName = this.getServletConfig().getServletName();
		int dot = servletName.lastIndexOf('.');
		if (dot >= 0) servletName = servletName.substring(dot + 1);
		executor.setName(servletName);
		this.client.setExecutor(executor);

		this.client.setFollowRedirects(false);
		this.client.setCookieStore(new HttpCookieStore.Empty());
		this.client.setMaxConnectionsPerDestination(32768);
		this.client.setIdleTimeout(30000);

		try {
			this.client.start();
			this.client.getContentDecoderFactories().clear();
		} catch (Exception x) {
			throw new ServletException(x);
		}
	}

	/** 由此扩展 */
	public CommunicationHandler getHandler(String serverName, String uri) {
		return new CommunicationHandler(serverName, uri) {
			@Override
			public void onSuccess(HttpServletRequest httpRequest, HttpServletResponse httpResponse, Map<String, String> headers, ByteArrayOutputStream requestBody, ByteArrayOutputStream responseBody) throws IOException {
				//nothing to do
			}
		};
	}

	@Override
	protected void service(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException, IOException {
		AsyncContext asyncContext = httpRequest.startAsync();
		asyncContext.setTimeout(0);

		String url = httpRequest.getRequestURL() + FunctionUtils.notNull(httpRequest.getQueryString(), query -> "?" + query, "");
		URI targetUri = URI.create(url);

		new ProxyRequestHandler(httpRequest, httpResponse, targetUri).send();
		asyncContext.complete();
	}

	protected class ProxyRequestHandler extends Response.Listener.Empty {
		private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
		private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
		private final ByteArrayOutputStream contentBuffer = new ByteArrayOutputStream();
		private final Map<String, String> headers = new HashMap<>();

		private final URI targetUri;
		private final HttpServletRequest httpRequest;
		private final HttpServletResponse httpResponse;
		private final InputStream httpRequestInputStream;
		private final CommunicationHandler handler;
		private boolean retryEnabled = true;
		private static final int REQUEST_CONTENT_SIZE_LIMIT = 256 * 1024;

		public void onRequestContent(Request request, ByteBuffer content) {
			if (this.handler.storeRequestBody()) {
				byte[] buffer;
				int offset;
				int length = content.remaining();
				if (content.hasArray()) {
					buffer = content.array();
					offset = content.arrayOffset();
				} else {
					buffer = new byte[length];
					content.get(buffer);
					offset = 0;
				}
				this.requestBody.write(buffer, offset, length);
			}
		}

		private Request createProxyRequest(HttpServletRequest httpRequest, URI targetUri, InputStreamContentProvider contentProvider) {
			Request proxyRequest = ProxyServerServlet.this.client.newRequest(targetUri).method(HttpMethod.fromString(httpRequest.getMethod())).version(HttpVersion.fromString(httpRequest.getProtocol()));

			for (Enumeration<String> headerNames = httpRequest.getHeaderNames(); headerNames.hasMoreElements();) {
				String headerName = headerNames.nextElement();
				if (HOP_HEADERS.stream().noneMatch(name -> name.equalsIgnoreCase(headerName))) {
					for (Enumeration<String> headerValues = httpRequest.getHeaders(headerName); headerValues.hasMoreElements();) {
						String headerValue = headerValues.nextElement();
						if (headerValue != null) {
							proxyRequest.header(headerName, headerValue);
						}
					}
				}
			}

			proxyRequest.content(contentProvider);
			proxyRequest.timeout(ProxyServerServlet.this.config.getProxyTimeOut(), TimeUnit.MILLISECONDS);
			return proxyRequest;
		}

		private void send() {
			Request proxyRequest = this.createProxyRequest(this.httpRequest, this.targetUri, new InputStreamContentProvider(this.httpRequestInputStream) {
				@Override
				public long getLength() {
					return ProxyRequestHandler.this.httpRequest.getContentLength();
				}

				@Override
				protected ByteBuffer onRead(byte[] buffer, int offset, int length) {
					if (length > 0) {
						if (ProxyRequestHandler.this.contentBuffer.size() < REQUEST_CONTENT_SIZE_LIMIT) {
							ProxyRequestHandler.this.contentBuffer.write(buffer, offset, length);
						} else {
							ProxyRequestHandler.this.retryEnabled = false;
						}
					}
					return super.onRead(buffer, offset, length);
				}
			});
			proxyRequest.onRequestContent(this::onRequestContent);
			proxyRequest.send(this);
		}

		public ProxyRequestHandler(HttpServletRequest httpRequest, HttpServletResponse httpResponse, URI targetUri) throws IOException {
			this.targetUri = targetUri;
			this.httpRequest = httpRequest;
			this.httpResponse = httpResponse;
			this.httpRequestInputStream = httpRequest.getInputStream();
			this.handler = ProxyServerServlet.this.getHandler(httpRequest.getServerName(), httpRequest.getRequestURI());
		}

		@Override
		public void onBegin(Response proxyResponse) {
			//有回应,则不retry
			this.retryEnabled = false;
			this.httpResponse.setStatus(proxyResponse.getStatus());
		}

		@Override
		public void onHeaders(Response proxyResponse) {
			if (this.handler.storeResponseHeaders()) {
				this.filterHeaders(proxyResponse, this.headers::put);
			}
			this.handler.onHeaders(proxyResponse, this.httpResponse, (p, h) -> this.filterHeaders(p, h::addHeader));
		}

		private void filterHeaders(Response proxyResponse, BiConsumer<String, String> headHandler) {
			proxyResponse.getHeaders().forEach(field -> {
				String headerName = field.getName();
				if (HOP_HEADERS.stream().noneMatch(name -> name.equalsIgnoreCase(headerName))) {
					String headerValue = field.getValue();
					if ((headerValue != null) && (headerValue.trim().length() != 0)) {
						headHandler.accept(headerName, headerValue);
					}
				}
			});
		}

		@Override
		public void onContent(Response proxyResponse, ByteBuffer content) {
			byte[] buffer;
			int offset;
			int length = content.remaining();
			if (content.hasArray()) {
				buffer = content.array();
				offset = content.arrayOffset();
			} else {
				buffer = new byte[length];
				content.get(buffer);
				offset = 0;
			}

			if (this.handler.storeResponseBody()) {
				this.responseBody.write(buffer, offset, length);
			}

			try {
				this.handler.onContent(this.httpResponse, buffer, offset, length);
			} catch (IOException e) {
				proxyResponse.abort(e);
				e.printStackTrace();
			}
		}

		@Override
		public void onSuccess(Response proxyResponse) {
			try {
				this.handler.onSuccess(this.httpRequest, this.httpResponse, this.headers, this.requestBody, this.responseBody);
			} catch (IOException e) {
				proxyResponse.abort(e);
				e.printStackTrace();
			}
		}

		@Override
		public void onFailure(Response proxyResponse, Throwable failure) {
			if (this.retryEnabled && (failure instanceof EOFException) && (HttpVersion.fromString(this.httpRequest.getProtocol()) == HttpVersion.HTTP_1_1)) {
				return;
			}

			this.retryEnabled = false;
			if (!this.httpResponse.isCommitted()) {
				if (failure instanceof TimeoutException) {
					this.httpResponse.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
				} else {
					this.httpResponse.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
				}
			}
		}

		@Override
		public void onComplete(Result result) {
			if (this.retryEnabled) {
				this.retryEnabled = false;//不第二次retry
				Request proxyRequest = this.createProxyRequest(this.httpRequest, this.targetUri,
						new InputStreamContentProvider(new SequenceInputStream(new ByteArrayInputStream(this.contentBuffer.toByteArray()), this.httpRequestInputStream)) {
							@Override
							public long getLength() {
								return ProxyRequestHandler.this.httpRequest.getContentLength();
							}
						});
				//onRequestContent(this::onRequestContent) 重复使用,会导致 requestBody 重复记录,所以重置 requestBody
				this.requestBody.reset();
				proxyRequest.onRequestContent(this::onRequestContent);
				proxyRequest.send(this);
			}
		}
	}
}
