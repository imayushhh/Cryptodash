package com.example.crypto;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CryptoController {

    private final CryptoService cryptoService;
    private final LocationService locationService;

    public CryptoController(CryptoService cryptoService, LocationService locationService) {
        this.cryptoService = cryptoService;
        this.locationService = locationService;
    }

    @GetMapping("/api/crypto")
    public List<CryptoCurrency> getCryptoPrices(@RequestParam(name = "currency", defaultValue = "usd") String currency) {
        return cryptoService.getTopCryptos(currency);
    }

    @GetMapping("/api/market/global")
    public GlobalMarketData getGlobalMarketData() {
        return cryptoService.getGlobalMarketData();
    }

    @GetMapping("/api/market/fear-greed")
    public FearGreedData getFearGreedIndex() {
        return cryptoService.getFearGreedIndex();
    }

    @GetMapping("/api/exchanges")
    public List<ExchangeInfo> getExchanges() {
        return cryptoService.getExchanges();
    }

    @GetMapping("/api/locations")
    public List<LocationInfo> getLocations(@RequestParam(name = "city", required = false) String city,
                                           @RequestParam(name = "type", required = false) String type,
                                           @RequestParam(name = "q", required = false) String query) {
        return locationService.getLocations(city, type, query);
    }
}
