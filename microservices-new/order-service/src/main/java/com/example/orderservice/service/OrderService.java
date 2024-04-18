package com.example.orderservice.service;

import brave.Span;
import brave.Tracer;
import com.example.orderservice.dto.InventoryResponse;
import com.example.orderservice.dto.OrderDTO;
import com.example.orderservice.model.Order;
import com.example.orderservice.model.OrderLineItems;
import com.example.orderservice.dto.OrderLineItemsDTO;
import com.example.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;


    public String placeOrder(OrderDTO orderDTO)
    {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItemsList = orderDTO.getOrderLineItemsDTOList()
                .stream()
                .map(this::mapToDTO)
                .toList();

        order.setOrderLineItemsList(orderLineItemsList);

        List<String> skuCodes = order.getOrderLineItemsList()
                .stream()
                .map(OrderLineItems::getSkuCode)
                .toList();

        log.info("Calling inventory service");

        Span inventoryServiceLookup = tracer.nextSpan().name("InventoryServiceLookup");
        try(Tracer.SpanInScope isLookup = tracer.withSpanInScope(inventoryServiceLookup.start()))
        {
            inventoryServiceLookup.tag("call", "inventory-service");

            // Call inventory-service and place order if product is in stock
            InventoryResponse[] inventoryResponses = webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory",
                            uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes)
                                    .build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();

            boolean allProductsInStock = Arrays.stream(inventoryResponses)
                    .allMatch(InventoryResponse::isInStock);

            if(allProductsInStock)
            {
                this.orderRepository.save(order);
                return "Order placed successfully.";
            }
            else
            {
                throw new IllegalArgumentException("Product not in stock");
            }

        }
        finally
        {
            inventoryServiceLookup.flush();
        }


    }


    private OrderLineItems mapToDTO(OrderLineItemsDTO orderLineItemsDTO)
    {
        return OrderLineItems.builder()
                .price(orderLineItemsDTO.getPrice())
                .quantity(orderLineItemsDTO.getQuantity())
                .skuCode(orderLineItemsDTO.getSkuCode())
                .build();

    }

}
