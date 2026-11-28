package com.mealplanner.service;

import com.mealplanner.tool.IngredientPriceTools;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Imports the Ministry of Agriculture's publicly published daily wholesale price bulletin. */
@Component
@ConditionalOnProperty(name = "mealplanner.prices.moa-sync.enabled", havingValue = "true", matchIfMissing = true)
public class MoAWholesalePriceSync {

	private static final Logger log = LoggerFactory.getLogger(MoAWholesalePriceSync.class);
	private static final URI BULLETIN_INDEX = URI.create("https://scs.moa.gov.cn/jcyj/");
	private static final Pattern ARTICLE_PATH = Pattern.compile("/jcyj/\\d{6}/t\\d{8}_\\d+\\.htm");
	private static final Pattern PRICE = Pattern.compile(
			"(猪肉|牛肉|羊肉|鸡蛋|白条鸡|鲫鱼|鲤鱼|白鲢鱼|大带鱼)(?:平均价格为)?\\s*([\\d. ]+)\\s*元/公斤");
	private static final Pattern DATE = Pattern.compile("日期：\\s*(\\d{4}-\\d{2}-\\d{2})");
	private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

	private final IngredientPriceTools priceTools;
	private final HttpClient http = buildHttpClient();
	private volatile LocalDate lastUpdated;

	public MoAWholesalePriceSync(IngredientPriceTools priceTools) {
		this.priceTools = priceTools;
	}

	private static HttpClient buildHttpClient() {
		HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
		if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
			try {
				KeyStore roots = KeyStore.getInstance("Windows-ROOT");
				roots.load(null, null);
				TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				trust.init(roots);
				SSLContext context = SSLContext.getInstance("TLS");
				context.init(null, trust.getTrustManagers(), null);
				builder.sslContext(context);
			}
			catch (Exception ex) {
				log.warn("Windows root certificate store unavailable; using the JVM default trust store: {}", ex.getMessage());
			}
		}
		return builder.build();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void syncOnStartup() {
		syncDailyPrices();
	}

	@Scheduled(cron = "0 30 15 * * *", zone = "Asia/Shanghai")
	public void syncDailyPrices() {
		try {
			String index = get(BULLETIN_INDEX);
			URI article = latestBulletin(index);
			if (article == null) {
				throw new IllegalStateException("农业农村部价格日报链接未找到");
			}
			String body = htmlText(get(article));
			Matcher dateMatch = DATE.matcher(body);
			if (!dateMatch.find()) {
				throw new IllegalStateException("价格日报中未找到发布日期");
			}
			LocalDate date = LocalDate.parse(dateMatch.group(1));
			if (date.equals(lastUpdated)) {
				return;
			}
			List<String> updated = new ArrayList<>();
			Matcher matcher = PRICE.matcher(body);
			while (matcher.find()) {
				Matcher number = NUMBER.matcher(matcher.group(2));
				if (!number.find()) continue;
				double yuanPerKg = Double.parseDouble(matcher.group(2).replaceAll("\\s+", ""));
				String name = matcher.group(1);
				priceTools.updatePublishedPrice(name, "500g", yuanPerKg / 2.0, category(name), date);
				updated.add(name);
			}
			if (updated.isEmpty()) {
				throw new IllegalStateException("价格日报未解析到可用的单品价格");
			}
			lastUpdated = date;
			log.info("Imported {} Ministry of Agriculture wholesale prices for {} from {}", updated.size(), date, article);
		}
		catch (Exception ex) {
			// Keep the last known/sample prices when the public site is unavailable or changes format.
			log.warn("Could not refresh Ministry of Agriculture wholesale prices; keeping existing prices: {}", ex.getMessage());
		}
	}

	private String get(URI uri) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
				.header("User-Agent", "MealPlanner/1.0 (public daily price bulletin reader)")
				.header("Accept", "text/html,application/xhtml+xml").GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("HTTP " + response.statusCode() + " from " + uri);
		}
		return response.body();
	}

	private URI latestBulletin(String html) throws Exception {
		final URI[] found = { null };
		final StringBuilder anchor = new StringBuilder();
		new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
			private String href;
			@Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
				if (tag == HTML.Tag.A) { href = (String) attrs.getAttribute(HTML.Attribute.HREF); anchor.setLength(0); }
			}
			@Override public void handleText(char[] data, int pos) {
				if (href != null) anchor.append(data);
			}
			@Override public void handleEndTag(HTML.Tag tag, int pos) {
				if (tag == HTML.Tag.A && href != null) {
					URI resolved = BULLETIN_INDEX.resolve(href);
					if (found[0] == null && anchor.toString().contains("农产品批发价格200指数")
							&& ARTICLE_PATH.matcher(resolved.getPath()).find()) {
						found[0] = resolved;
					}
					href = null;
				}
			}
		}, true);
		return found[0];
	}

	private String htmlText(String html) throws Exception {
		StringBuilder text = new StringBuilder();
		new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
			@Override public void handleText(char[] data, int pos) { text.append(data).append(' '); }
		}, true);
		return text.toString();
	}

	private String category(String name) {
		return switch (name) {
			case "猪肉", "牛肉", "羊肉", "白条鸡" -> "肉禽";
			case "鸡蛋" -> "蛋豆";
			default -> "水产";
		};
	}
}
