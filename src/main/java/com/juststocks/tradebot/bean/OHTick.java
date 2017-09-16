package com.juststocks.tradebot.bean;

import com.rainmatter.models.Tick;

/**
 * @author bharath_kandasamy
 *
 */

public class OHTick extends OHLTick {
	public OHTick(Tick tick) {
		super(tick);
	}
	
	public boolean isOH() {
		return tick.getOpenPrice() == tick.getHighPrice();
	}

	@Override
	public int compareTo(OHLTick o) {
		OHTick otherTick = (OHTick) o;
		if (tick.getToken() == otherTick.getToken()) {
			return 0;
		} else if (!lenientNLHC
				&& getNetLowChange() < o.getNetLowChange()
				&& getNetHighChange() < o.getNetHighChange()
				&& (!totalBuySellConstraintEnabled || !isTbGreaterThanTs())) {
			return -1;
		} else if (lenientNLHC
				&& (getNetLowChange() < o.getNetLowChange() || getNetHighChange() < o.getNetHighChange())
				&& (!totalBuySellConstraintEnabled || !isTbGreaterThanTs())) {
			return -1;
		} else {
			return 1;
		}
	}
}