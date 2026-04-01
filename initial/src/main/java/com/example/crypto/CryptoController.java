package com.example.crypto;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CryptoController {

    private final CryptoService cryptoService;

    public CryptoController(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
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
}
