/**
 * 
 */
package com.juststocks.tradebot.facade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.juststocks.tradebot.akka.TickDispenserActor;
import com.juststocks.tradebot.bean.KiteProperties;
import com.juststocks.tradebot.bean.OHLTick;
import com.juststocks.tradebot.bean.response.kiteconnect.KiteResponse;
import com.juststocks.tradebot.bean.response.kiteconnect.ParameterData;
import com.juststocks.tradebot.constants.OHLStrategyEnum;
import com.juststocks.tradebot.exception.AuthException;
import com.juststocks.tradebot.util.ApacheHttpUtil;
import com.juststocks.tradebot.util.SpringRestTemplateUtil;
import com.neovisionaries.ws.client.WebSocketException;
import com.rainmatter.kiteconnect.KiteConnect;
import com.rainmatter.kitehttp.SessionExpiryHook;
import com.rainmatter.kitehttp.exceptions.KiteException;
import com.rainmatter.models.IndicesQuote;
import com.rainmatter.models.Instrument;
import com.rainmatter.models.Tick;
import com.rainmatter.models.UserModel;
import com.rainmatter.ticker.KiteTicker;
import com.rainmatter.ticker.OnConnect;
import com.rainmatter.ticker.OnDisconnect;
import com.rainmatter.ticker.OnTick;

import akka.actor.ActorRef;

/**
 * @author bharath_kandasamy
 *
 */
@Service
public class KiteTradeSystemFacade implements TradeSystemFacade, SessionExpiryHook, OnConnect, OnDisconnect, OnTick {
	private static final Logger MAIN_LOGGER = LoggerFactory.getLogger(LOGGER_MAIN);
	private static final Logger ORDER_LOGGER = LoggerFactory.getLogger(LOGGER_ORDER);
	
	@Autowired
	public KiteProperties properties;

	@Autowired
	public ApacheHttpUtil apacheHttpUtil;

	@Autowired
	private SpringRestTemplateUtil springRestTemplateUtil;

	private KiteConnect kiteConnect;

	private KiteTicker kiteTicker;

	public boolean webSocketConnected;

	@Autowired
	@Qualifier(AKKA_TICK_DISPENSER_ACTOR_REF)
	private ActorRef tickDispenserActorRef;

	@Autowired
	@Qualifier(AKKA_ORDER_ACTOR_REF)
	private ActorRef orderActorRef;

	public KiteConnect getKiteConnect() {
		return kiteConnect;
	}

	public void setKiteConnect(KiteConnect kiteConnect) {
		this.kiteConnect = kiteConnect;
	}

	@Override
	public boolean login() {
		return (properties.getRequestToken() != null);
	}

	@Override
	public boolean authenticate() throws AuthException {
		MAIN_LOGGER.info(METHOD_ENTRY);
		this.kiteConnect = new KiteConnect(properties.getApikey());
		kiteConnect.setUserId(properties.getUserId());
		UserModel userModel = null;
		if (StringUtils.isEmpty(properties.getAccessToken()) && StringUtils.isEmpty(properties.getPublicToken())) {
			try {
				userModel = kiteConnect.requestAccessToken(properties.getRequestToken(), properties.getApiSecret());
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (KiteException e) {
				throw new AuthException(EXCEPTION_AUTH_KITE.replace(REPLACE_HOLDER_CODE, String.valueOf(e.code))
						.replace(REPLACE_HOLDER_ERROR, e.message));
			}
			MAIN_LOGGER.info(KITE_CONNECT_AT_PT, userModel.accessToken, userModel.publicToken);
			MAIN_LOGGER.info(METHOD_EXIT);
			return false;
			// TODO
			// kiteConnect.setAccessToken(userModel.accessToken);
			// kiteConnect.setPublicToken(userModel.publicToken);
			//
			// properties.setAccessToken(userModel.accessToken);
			// properties.setPublicToken(userModel.publicToken);
		} else {
			kiteConnect.setAccessToken(properties.getAccessToken());
			kiteConnect.setPublicToken(properties.getPublicToken());
		}
		kiteConnect.registerHook(this);
		MAIN_LOGGER.info(METHOD_EXIT);
		return (kiteConnect.getAccessToken() != null);
	}

	@Override
	public void sessionExpired() {
		MAIN_LOGGER.info(METHOD_ENTRY);
		MAIN_LOGGER.error(SESSION_EXPIRED);
		MAIN_LOGGER.info(METHOD_EXIT);
	}

	@Override
	public boolean loadParameters() {
		MAIN_LOGGER.info(METHOD_ENTRY);
		KiteResponse<ParameterData> response = springRestTemplateUtil
				.exchange(properties.getApiEndpoint(properties.getParameterApiPath()), HttpMethod.GET, null,
						new ParameterizedTypeReference<KiteResponse<ParameterData>>() {
						})
				.getBody();
		properties.setParameterData(response.getData());
		MAIN_LOGGER.info(RESPONSE_PARAMETERS, response.getData().toString());
		MAIN_LOGGER.info(METHOD_EXIT);
		return true;
	}

	@Override
	public boolean getInstruments() {
		// TODO
		return false;
	}

	@Override
	public boolean getInstruments(String exchange) {
		MAIN_LOGGER.info(METHOD_ENTRY);
		List<Instrument> instruments = null;
		try {
			instruments = kiteConnect.getInstruments(exchange);
			properties.setInstrumentsMap(exchange, instruments);
			MAIN_LOGGER.debug(RESPONSE_EXCHANGE_INSTRUMENTS, exchange, instruments);
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (KiteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		MAIN_LOGGER.info(METHOD_EXIT);
		return instruments.size() > 0;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T> T getQuoteIndices(Long token) {
		IndicesQuote indicesQuote = null;
		try {
			indicesQuote = kiteConnect.getQuoteIndices(
					properties.getParameterData().getExchange().get(properties.getOhlStrategyExchangeValueIndex())
					, properties.getTradingInstrumentMap().get(token).getTradingsymbol());
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (KiteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return (T) indicesQuote;
	}

	@Override
	public boolean initWebSocket() {
		MAIN_LOGGER.info(METHOD_ENTRY);
		boolean connected = false;
		this.kiteTicker = new KiteTicker(kiteConnect);
		kiteTicker.setOnConnectedListener(this);
		kiteTicker.setOnDisconnectedListener(this);
		kiteTicker.setOnTickerArrivalListener(this);
		try {
			kiteTicker.connect();
			connected = true;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (WebSocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		MAIN_LOGGER.info(METHOD_EXIT);
		return connected;
	}

	@Override
	public void onConnected() {
		MAIN_LOGGER.info(METHOD_ENTRY);
		this.webSocketConnected = true;
		MAIN_LOGGER.info(WEB_SOCKECT_CONNECTION_SUCCESS);
		MAIN_LOGGER.info(METHOD_EXIT);
	}

	@Override
	public boolean subscribeInstruments(ArrayList<Long> tokens) {
		MAIN_LOGGER.info(METHOD_ENTRY);
		boolean subscribed = false;
		try {
			kiteTicker.setMode(tokens, KiteTicker.modeFull);
			kiteTicker.subscribe(tokens);
			subscribed = true;
			MAIN_LOGGER.debug(INSTRUMENTS_SUBSCRIBED, tokens.size(), tokens.toString());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (WebSocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (KiteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		MAIN_LOGGER.info(METHOD_EXIT);
		return subscribed;
	}

	@Override
	public boolean unsubscribeInstruments(ArrayList<Long> tokens) {
		MAIN_LOGGER.debug(METHOD_ENTRY);
		boolean unsubscribed = false;
		kiteTicker.unsubscribe(tokens);
		unsubscribed = true;
		MAIN_LOGGER.debug(METHOD_EXIT);
		return unsubscribed;

	}

	@Override
	public void onTick(ArrayList<Tick> ticks) {
		MAIN_LOGGER.debug(METHOD_ENTRY);
		if (ticks.size() > 0) {
			MAIN_LOGGER.info(KITE_ON_TICK_SIZE, ticks.size());
			tickDispenserActorRef.tell(new TickDispenserActor.MyArrayList(ticks), ActorRef.noSender());
		}
		MAIN_LOGGER.debug(METHOD_EXIT);
	}

	@Override
	@Scheduled(cron = CRON_ENTRY_OHL_STRATEGY_ORDERS)
	public void triggerOHLStrategyOrders() {
		orderActorRef.tell(ACTOR_ORDER_MSG_TYPE_OHL_STRATEGY, ActorRef.noSender());
	}

	@Override
	public void onDisconnected() {
		MAIN_LOGGER.info(METHOD_ENTRY);
		this.webSocketConnected = false;
		MAIN_LOGGER.error(WEB_SOCKECT_DISCONNECTION);
		MAIN_LOGGER.info(METHOD_EXIT);
	}

	@Override
	public Map<String, Object> buildOrderParamMap(
			String exchange, 
			String tradingsymbol,
			String transactionType,
			int quantity,
			double price,
			String product,
			String orderType,
			String validity,
			int disclosedQuantity,
			double triggerPrice,
			double squareoffValue,
			double stoplossValue,
			double trailingStoploss
			) {
		Map<String, Object> paramMap = new HashMap<>();
		paramMap.put(ParameterData.NameEnum.EXCHANGE.getName(), exchange);
		paramMap.put(ParameterData.NameEnum.TRADINGSYMBOL.getName(), tradingsymbol);
		paramMap.put(ParameterData.NameEnum.TRANSACTION_TYPE.getName(), transactionType);
		paramMap.put(ParameterData.NameEnum.QUANTITY.getName(), quantity);
		paramMap.put(ParameterData.NameEnum.PRICE.getName(), price);
		paramMap.put(ParameterData.NameEnum.PRODUCT.getName(), product);
		paramMap.put(ParameterData.NameEnum.ORDER_TYPE.getName(), orderType);
		paramMap.put(ParameterData.NameEnum.VALIDITY.getName(), validity);
		if (disclosedQuantity >= 0)
			paramMap.put(ParameterData.NameEnum.DISCLOSED_QUANTITY.getName(), disclosedQuantity);
		if (triggerPrice >= 0)
			paramMap.put(ParameterData.NameEnum.TRIGGER_PRICE.getName(), triggerPrice);
		if (squareoffValue >= 0)
			paramMap.put(ParameterData.NameEnum.SQUAREOFF_VALUE.getName(), squareoffValue);
		if (stoplossValue >= 0)
			paramMap.put(ParameterData.NameEnum.STOPLOSS_VALUE.getName(), stoplossValue);
		if (trailingStoploss >= 0)
			paramMap.put(ParameterData.NameEnum.TRAILING_STOPLOSS.getName(), trailingStoploss);
		return paramMap;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <E> boolean placeOHLOrder(Collection<E> ohlTickCollection, final int tradeCount, final ParameterData.ValueIndexEnum transactionType, int ohlSign) {
		Tick tick;
		int tradedCount = 0;
		IndicesQuote indicesQuote;
		int tradeQuantity = 0;
		double price = -1;
		double squareoffValue = -1;
		double stoplossValue = -1;
		double trailingStoploss = -1;
		for (OHLTick ohlTick : (Collection<OHLTick>) ohlTickCollection) {
			tick = ohlTick.tick;
			if (++tradedCount > tradeCount) {
				break;
			}
			indicesQuote = this.<IndicesQuote> getQuoteIndices(tick.getToken());
			tick.setLastTradedPrice(indicesQuote.lastPrice);
			if (properties.getOhlTradeOrderTypeValueIndex() == ParameterData.ValueIndexEnum.ORDER_TYPE_LIMIT.getIndex()) {
				if (transactionType.getIndex() == ParameterData.ValueIndexEnum.TRANSACTION_TYPE_BUY.getIndex()) {
					price = tick.getMarketDepth()
							.get(properties.getParameterData().getTransactionType().get(ParameterData.ValueIndexEnum.TRANSACTION_TYPE_SELL.getIndex()).toLowerCase())
							.get(0)
							.getPrice();
				}
				if (transactionType.getIndex() == ParameterData.ValueIndexEnum.TRANSACTION_TYPE_SELL.getIndex()) {
					price = tick.getMarketDepth()
							.get(properties.getParameterData().getTransactionType().get(ParameterData.ValueIndexEnum.TRANSACTION_TYPE_BUY.getIndex()).toLowerCase())
							.get(0)
							.getPrice();
				}
			}
			if (properties.getOhlStrategyInstrumentType().equals(OHLStrategyEnum.INSTRUMENT_TYPE_EQ.getValue())) {
				if (properties.getOhlTradeType() == Integer.valueOf(OHLStrategyEnum.TRADE_TYPE_QUANTITY.getValue())) {
					tradeQuantity = properties.getOhlTradeQtyPerInstrument();
				} else if (properties.getOhlTradeType() == Integer.valueOf(OHLStrategyEnum.TRADE_TYPE_VALUE.getValue())) {
					tradeQuantity = (int) (properties.getOhlTradeValuePerInstrument() / tick.getLastTradedPrice());
				} else if (properties.getOhlTradeType() == Integer.valueOf(OHLStrategyEnum.TRADE_TYPE_TOTAL_VALUE.getValue())) {
					tradeQuantity = (int) (properties.getOhlTradeTotalValue() / tradeCount / tick.getLastTradedPrice());
				}
			}
			if (properties.getOhlStrategyInstrumentType().equals(OHLStrategyEnum.INSTRUMENT_TYPE_FUT.getValue())) {
				tradeQuantity = properties.getTradingInstrumentMap().get(tick.getToken()).getLot_size();
			}
			if (properties.getOhlTradeOrderVarietyValueIndex() == ParameterData.ValueIndexEnum.ORDER_VARIETY_BO.getIndex()) {
				squareoffValue = tick.getLastTradedPrice() * properties.getOhlTradeBOTargetPercent() / 100;
				stoplossValue = Math.abs(tick.getLastTradedPrice() - (tick.getOpenPrice() - (ohlSign * properties.getOhlTradeBOExtraStoploss())));
				trailingStoploss = properties.getOhlTradeBOTrailingStoploss();
			}
			try {
				kiteConnect.placeOrder(
						buildOrderParamMap(
								properties.getParameterData().getExchange().get(properties.getOhlStrategyExchangeValueIndex())
								, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol()
								, properties.getParameterData().getTransactionType().get(transactionType.getIndex())
								, tradeQuantity
								, price + 1
								, properties.getParameterData().getProduct().get(ParameterData.ValueIndexEnum.PRODUCT_MIS.getIndex())
								, properties.getParameterData().getOrderType().get(ParameterData.ValueIndexEnum.ORDER_TYPE_LIMIT.getIndex())
								, properties.getParameterData().getValidity().get(ParameterData.ValueIndexEnum.VALIDITY_IOC.getIndex())
								, -1
								, -1
								, squareoffValue
								, stoplossValue
								, trailingStoploss)
				, properties.getParameterData().getOrderVariety().get(ParameterData.ValueIndexEnum.ORDER_VARIETY_REGULAR.getIndex()));
			} catch (KiteException e) {
				ORDER_LOGGER.error(EXCEPTION_ORDER_KITE.replace(REPLACE_HOLDER_CODE, String.valueOf(e.code))
						.replace(REPLACE_HOLDER_ERROR, e.message)
						, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol());
			}
		}
		return false;
	}

}
