package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;

public abstract class CommunicationHandler {
	public final String serverName;
	public final String uri;

	public CommunicationHandler(String serverName, String uri) {
		this.serverName = serverName;
		this.uri = uri;
	}

	public void onHeaders(Response proxyResponse, HttpServletResponse httpResponse, BiConsumer<Response, HttpServletResponse> defaultOnHeaders) {
		defaultOnHeaders.accept(proxyResponse, httpResponse);
	}

	public void onContent(HttpServletResponse httpResponse, byte[] buffer, int offset, int length) throws IOException {
		httpResponse.getOutputStream().write(buffer, offset, length);
	}

	public abstract void onSuccess(HttpServletRequest httpRequest, HttpServletResponse httpResponse, Map<String, String> headers, ByteArrayOutputStream requestBody, ByteArrayOutputStream responseBody) throws IOException;

	/** if false, onSuccess 的参数 requestBody 无内容 */
	public boolean storeRequestBody() {
		return false;
	}

	/** if false, onSuccess 的参数 responseBody 无内容 */
	public boolean storeResponseBody() {
		return false;
	}

	/** if false, onSuccess 的参数 headers 无内容 */
	public boolean storeResponseHeaders() {
		return false;
	}
}
