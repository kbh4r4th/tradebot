/**
 * 
 */
package com.juststocks.tradebot.facade;

import static com.juststocks.tradebot.bean.ZerodhaProperties.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.util.Precision;
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

import com.juststocks.tradebot.actor.TickDispenserActor;
import com.juststocks.tradebot.actor.TradeGeneratorActor.TradeTriggerData;
import com.juststocks.tradebot.bean.ZerodhaProperties;
import com.juststocks.tradebot.bean.OHLTick;
import com.juststocks.tradebot.bean.response.kiteconnect.KiteResponse;
import com.juststocks.tradebot.bean.response.kiteconnect.ParameterData;
import com.juststocks.tradebot.constants.TradeTriggerEnum;
import com.juststocks.tradebot.exception.AuthException;
import com.juststocks.tradebot.util.SpringRestTemplateUtil;
import com.neovisionaries.ws.client.WebSocketException;
import com.rainmatter.kiteconnect.KiteConnect;
import com.rainmatter.kitehttp.SessionExpiryHook;
import com.rainmatter.kitehttp.exceptions.KiteException;
import com.rainmatter.models.IndicesQuote;
import com.rainmatter.models.Instrument;
import com.rainmatter.models.Order;
import com.rainmatter.models.Quote;
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
public class KiteTradeFacade implements TradeFacade, SessionExpiryHook, OnConnect, OnDisconnect, OnTick {
	private static final Logger MAIN_LOGGER = LoggerFactory.getLogger(LOGGER_MAIN);
	private static final Logger ORDER_LOGGER = LoggerFactory.getLogger(LOGGER_ORDER);
	
	@Autowired
	public ZerodhaProperties properties;

	@Autowired
	private SpringRestTemplateUtil springRestTemplateUtil;

	private KiteConnect kiteConnect;

	private KiteTicker kiteTicker;

	public boolean webSocketConnected;

	@Autowired
	@Qualifier(BEAN_AKKA_TICK_DISPENSER_ACTOR_REF)
	private ActorRef tickDispenserActorRef;

	@Autowired
	@Qualifier(BEAN_AKKA_TRADE_GENERATOR_ACTOR_REF)
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
	public boolean loadExchangeInstruments(String exchange) {
		MAIN_LOGGER.info(METHOD_ENTRY);
		List<Instrument> instruments = null;
		try {
			instruments = kiteConnect.getInstruments(exchange);
			properties.setExchangeInstrumentMap(exchange, instruments);
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
		return instruments != null && instruments.size() > 0;
	}
	
	@Override
	public boolean loadLocalInstruments(String exchange) {
		MAIN_LOGGER.info(METHOD_ENTRY);
		List<Instrument> instruments = null;
		try {
			instruments = kiteConnect.getInstruments(exchange);
			properties.setLocalInstrumentMap(exchange, instruments);
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
		return instruments != null && instruments.size() > 0;
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
			ORDER_LOGGER.error(EXCEPTION_KITE.replace(REPLACE_HOLDER_CODE, String.valueOf(e.code))
					.replace(REPLACE_HOLDER_ERROR, e.message)
					, properties.getTradingInstrumentMap().get(token).getTradingsymbol());
		}
		return (T) indicesQuote;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T> T getQuote(Long token) {
		Quote quote = null;
		try {
			quote = kiteConnect.getQuote(
					properties.getParameterData().getExchange().get(properties.getOhlStrategyExchangeValueIndex())
					, properties.getTradingInstrumentMap().get(token).getTradingsymbol());
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (KiteException e) {
			ORDER_LOGGER.error(EXCEPTION_KITE.replace(REPLACE_HOLDER_CODE, String.valueOf(e.code))
					.replace(REPLACE_HOLDER_ERROR, e.message)
					, properties.getTradingInstrumentMap().get(token).getTradingsymbol());
		}
		return (T) quote;
	}

	@Override
	public boolean initWebSocket() {
		MAIN_LOGGER.info(METHOD_ENTRY);
		boolean connected = false;
		this.kiteTicker = new KiteTicker(kiteConnect);
		kiteTicker.setOnConnectedListener(this);
		kiteTicker.setOnDisconnectedListener(this);
		kiteTicker.setOnTickerArrivalListener(this);
		kiteTicker.setTryReconnection(true);
		kiteTicker.setMaxRetries(-1);
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
			Calendar calendar = Calendar.getInstance();
			if (!(calendar.get(Calendar.HOUR_OF_DAY) == 9
					&& calendar.get(Calendar.MINUTE) > 0
					&& calendar.get(Calendar.MINUTE) < 15)) {
				tickDispenserActorRef.tell(new TickDispenserActor.MyArrayList(ticks), ActorRef.noSender());
			}
		}
		MAIN_LOGGER.debug(METHOD_EXIT);
	}
	
	@Override
	@Scheduled(cron = CRON_ENTRY_OHL_TRADE_TRIGGER)
	public void scheduledOHLTradesTrigger() {
		orderActorRef.tell(ACTOR_TRADE_GENERATOR_MSG_TYPE_TRADE_OHL, ActorRef.noSender());
	}

	@Override
	public void triggerTrades(final TradeTriggerData tradeTriggerData) {
		orderActorRef.tell(tradeTriggerData, ActorRef.noSender());
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
	public <E> boolean placeOHLOrder(Collection<E> ohlTickCollection, final int tradeCount, int tradeQty, final ParameterData.ValueIndexEnum transactionType, int ohlSign) {
		Tick tick;
		String orderSign = null;
		int tradedCount = 0;
		Quote quote;
		int tradeQuantity = 0;
		double price = -1;
		double target = -1;
		double stoploss = -1;
		double trailingStopLoss = -1;
		double absoluteTarget = -1;
		double absoluteStopLoss = -1;
		Order order = null;
		List<Long> unsubscribeTicks = new ArrayList<>();
		for (OHLTick ohlTick : (Collection<OHLTick>) ohlTickCollection) {
			tick = ohlTick.tick;
			if (!properties.isOfflineTestingEnabled()) {
				quote = this.<Quote> getQuote(tick.getToken());
				try {
					Thread.sleep(450);
				} catch (InterruptedException e) {
					ORDER_LOGGER.debug(e.getMessage());
				}
				tick.setOpenPrice(quote.ohlc.open);
				tick.setLowPrice(quote.ohlc.low);
				tick.setHighPrice(quote.ohlc.high);
				tick.setTotalBuyQuantity(quote.buyQuantity);
				tick.setTotalSellQuantity(quote.sellQuantity);
				tick.setLastTradedPrice(quote.lastPrice);
				tick.setMarketDepth(quote.depth);	
			} else {
				price = tick.getLastTradedPrice();
				if (transactionType.getIndex() == ParameterData.ValueIndexEnum.TRANSACTION_TYPE_BUY.getIndex()) {
					orderSign = SYMBOL_PLUS;
				}
				if (transactionType.getIndex() == ParameterData.ValueIndexEnum.TRANSACTION_TYPE_SELL.getIndex()) {
					orderSign = SYMBOL_MINUS;
				}
			}
			if (!ohlTick.isOLOrOH()) {
				if (olTickMap.remove(tick.getToken()) != null) {
					ORDER_LOGGER.warn(STRATEGY_OHL_OL_REMOVED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol(), tick.getToken(),
							tick.getLastTradedPrice(), 
							tick.getLowPrice(), tick.getOpenPrice(), tick.getHighPrice(),
							tick.getToken());
				}
				if (ohTickMap.remove(tick.getToken()) != null) {
					ORDER_LOGGER.warn(STRATEGY_OHL_OH_REMOVED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol(), tick.getToken(),
							tick.getLastTradedPrice(), 
							tick.getLowPrice(), tick.getOpenPrice(), tick.getHighPrice(),
							tick.getToken());
				}
				unsubscribeTicks.add(tick.getToken());
				continue;
			}
			if (!(tick.getLastTradedPrice() >= properties.getOhlTradeMinInstrumentPrice()
					&& tick.getLastTradedPrice() <= properties.getOhlTradeMaxInstrumentPrice()
					&& ohlTick.getNetLowChange() <= properties.getOhlTradeableNLC()
					&& ohlTick.getNetHighChange() <= properties.getOhlTradeableNHC())) {
				continue;
			}
			if (nonOHLTickSet.contains(tick.getToken())
					|| orderedTickMap.containsKey(tick.getToken())) {
				continue;
			}
			if (tick.getLastTradedPrice() <= tick.getLowPrice()) {
				if (olTickMap.remove(tick.getToken()) != null) {
					nonOHLTickSet.add(tick.getToken());
					ORDER_LOGGER.warn(STRATEGY_OHL_OL_REMOVED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol(), tick.getToken(),
							tick.getLastTradedPrice(), 
							tick.getLowPrice(), tick.getOpenPrice(), tick.getHighPrice(),
							tick.getToken());
				}
				continue;
			}
			if (tick.getLastTradedPrice() >= tick.getHighPrice()) {
				if (ohTickMap.remove(tick.getToken()) != null) {
					nonOHLTickSet.add(tick.getToken());
					ORDER_LOGGER.warn(STRATEGY_OHL_OH_REMOVED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol(), tick.getToken(),
							tick.getLastTradedPrice(), 
							tick.getLowPrice(), tick.getOpenPrice(), tick.getHighPrice(),
							tick.getToken());
				}
				continue;
			}
			if (!properties.isOfflineTestingEnabled()) {
				if (properties.getOhlTradeOrderTypeValueIndex() == ParameterData.ValueIndexEnum.ORDER_TYPE_LIMIT.getIndex()) {
					if (transactionType.getIndex() == ParameterData.ValueIndexEnum.TRANSACTION_TYPE_BUY.getIndex()) {
						price = tick.getMarketDepth()
								.get(properties.getParameterData().getTransactionType().get(ParameterData.ValueIndexEnum.TRANSACTION_TYPE_BUY.getIndex()).toLowerCase())
								.get(0)
								.getPrice();
						price = Precision.round(price + 0.05, 2);
						if (properties.isOnlineTestingEnabled()) {
							price = tick.getLastTradedPrice();
						} else {
							if (price <= tick.getOpenPrice()) {
								continue;
							}
						}
						orderSign = SYMBOL_PLUS;
					}
					if (transactionType.getIndex() == ParameterData.ValueIndexEnum.TRANSACTION_TYPE_SELL.getIndex()) {
						price = tick.getMarketDepth()
								.get(properties.getParameterData().getTransactionType().get(ParameterData.ValueIndexEnum.TRANSACTION_TYPE_SELL.getIndex()).toLowerCase())
								.get(0)
								.getPrice();
						price = Precision.round(price - 0.05, 2);
						if (properties.isOnlineTestingEnabled()) {
							price = tick.getLastTradedPrice();
						} else {
							if (price >= tick.getOpenPrice()) {
								continue;
							}
						}
						orderSign = SYMBOL_MINUS;
					}
				}
			}
			if (properties.getOhlStrategyInstrumentType().equals(TradeTriggerEnum.INSTRUMENT_TYPE_EQ.getValue())) {
				if (properties.getOhlTradeType() == Integer.valueOf(TradeTriggerEnum.TRADE_TYPE_QUANTITY.getValue())) {
					tradeQuantity = tradeQty;
				} else if (properties.getOhlTradeType() == Integer.valueOf(TradeTriggerEnum.TRADE_TYPE_VALUE.getValue())) {
					tradeQuantity = (int) (properties.getOhlTradeValuePerInstrument() / tick.getLastTradedPrice());
				} else if (properties.getOhlTradeType() == Integer.valueOf(TradeTriggerEnum.TRADE_TYPE_TOTAL_VALUE.getValue())) {
					tradeQuantity = (int) (properties.getOhlTradeTotalValue() / tradeCount / tick.getLastTradedPrice());
				}
			}
			if (properties.getOhlStrategyInstrumentType().equals(TradeTriggerEnum.INSTRUMENT_TYPE_FUT.getValue())) {
				tradeQuantity = properties.getTradingInstrumentMap().get(tick.getToken()).getLot_size();
			}
			if (properties.getOhlTradeOrderVarietyValueIndex() == ParameterData.ValueIndexEnum.ORDER_VARIETY_BO.getIndex()) {
				target = Precision.round(Precision.round(price * properties.getOhlTradeBOTargetPercent() / 100, 1) - 0.05, 2);
				absoluteTarget = Precision.round(Math.abs(price + (ohlSign * target)), 2);
				
				absoluteStopLoss = Precision.round(tick.getOpenPrice() - (ohlSign * (0.05 + properties.getOhlTradeBOExtraStoploss())), 2);
				stoploss = Precision.round(Math.abs(price - absoluteStopLoss), 2);
				
				trailingStopLoss = properties.getOhlTradeBOTrailingStoploss();
			}
			try {
				if (!properties.isOnlineTestingEnabled()) {
					order = kiteConnect.placeOrder(
							buildOrderParamMap(
									properties.getParameterData().getExchange().get(properties.getOhlStrategyExchangeValueIndex())
									, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol()
									, properties.getParameterData().getTransactionType().get(transactionType.getIndex())
									, tradeQuantity
									, price
									, properties.getParameterData().getProduct().get(ParameterData.ValueIndexEnum.PRODUCT_MIS.getIndex())
									, properties.getParameterData().getOrderType().get(ParameterData.ValueIndexEnum.ORDER_TYPE_LIMIT.getIndex())
									, properties.getParameterData().getValidity().get(ParameterData.ValueIndexEnum.VALIDITY_IOC.getIndex())
									, -1
									, -1
									, target
									, stoploss
									, trailingStopLoss)
					, properties.getParameterData().getOrderVariety().get(properties.getOhlTradeOrderVarietyValueIndex()));
					orderedTickMap.put(tick.getToken(), order.orderId);
					olTickMap.remove(tick.getToken());
					ohTickMap.remove(tick.getToken());
					unsubscribeTicks.add(tick.getToken());
					try {
						Thread.sleep(450);
					} catch (InterruptedException e) {
						ORDER_LOGGER.debug(e.getMessage());
					}
				}
			} catch (KiteException e) {
				ORDER_LOGGER.error(EXCEPTION_ORDER_KITE.replace(REPLACE_HOLDER_CODE, String.valueOf(e.code))
						.replace(REPLACE_HOLDER_ERROR, e.message)
						, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol());
				ORDER_LOGGER.error(ORDER_GENERATION_FAILED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol()
						, properties.getParameterData().getTransactionType().get(transactionType.getIndex())
						, tradeQuantity
						, price
						, properties.getParameterData().getOrderVariety().get(properties.getOhlTradeOrderVarietyValueIndex()).toUpperCase()
						, target, absoluteTarget
						, stoploss, absoluteStopLoss
						, trailingStopLoss);
			}
			if (order != null || properties.isOnlineTestingEnabled()) {
				tradedCount++;
				ORDER_LOGGER.info(ORDER_GENERATED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol().toUpperCase()
						, properties.getParameterData().getTransactionType().get(transactionType.getIndex()) , tradedCount, orderSign
						, String.valueOf(ohlTick.getTotalNetLowHighChange())
						, tradeQuantity
						, price
						, properties.getParameterData().getOrderVariety().get(properties.getOhlTradeOrderVarietyValueIndex())
						, tick.getLowPrice()
						, tick.getHighPrice()
						, tick.getOpenPrice()
						, stoploss, absoluteStopLoss
						, target, absoluteTarget
						, trailingStopLoss);
			} else {
				ORDER_LOGGER.error(ORDER_GENERATION_FAILED, properties.getTradingInstrumentMap().get(tick.getToken()).getTradingsymbol()
						, properties.getParameterData().getTransactionType().get(transactionType.getIndex())
						, tradeQuantity
						, price
						, properties.getParameterData().getOrderVariety().get(properties.getOhlTradeOrderVarietyValueIndex()).toUpperCase()
						, stoploss, absoluteStopLoss
						, target, absoluteTarget
						, trailingStopLoss);
			}
			if (tradedCount >= tradeCount) {
				break;
			}
		}
		if (unsubscribeTicks.size() > 0) {
			unsubscribeInstruments((ArrayList<Long>) unsubscribeTicks);
		}
		return true;
	}


}
