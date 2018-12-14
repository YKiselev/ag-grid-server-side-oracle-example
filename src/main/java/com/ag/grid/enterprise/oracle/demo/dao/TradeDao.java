package com.ag.grid.enterprise.oracle.demo.dao;

import com.github.ykiselev.aggrid.domain.request.AgGridGetRowsRequest;
import com.github.ykiselev.aggrid.domain.response.AgGridGetRowsResponse;

/**
 * @author Yuriy Kiselev (uze@yandex.ru).
 */
public interface TradeDao {

    AgGridGetRowsResponse getData(AgGridGetRowsRequest request);
}
