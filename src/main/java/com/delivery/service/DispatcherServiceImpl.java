package com.delivery.service;

import com.delivery.dto.*;
import com.delivery.entity.Order;
import com.delivery.entity.User;
import com.delivery.exception.DriverNotFoundException;
import com.delivery.exception.OrderNotFoundException;
import com.delivery.exception.UserWithEmailNotFoundException;
import com.delivery.mapper.DispatcherMapper;
import com.delivery.mapper.OrderMapper;
import com.delivery.mapper.OrderStatusHistoryMapper;
import com.delivery.repository.OrderRepository;
import com.delivery.repository.UserRepository;
import com.delivery.util.OrderStatus;
import com.delivery.util.RoleValidator;
import jakarta.transaction.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class DispatcherServiceImpl implements DispatcherService {
    private final DispatcherMapper dispatcherMapper;
    private final OrderMapper orderMapper;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final RoleValidator roleValidator;
    private final PriceCalculatorService priceCalculatorService;
    private final OrderStatusHistoryMapper orderStatusHistoryMapper;
    private final OrderStatusHistoryService orderStatusHistoryService;

    public DispatcherServiceImpl(DispatcherMapper dispatcherMapper, OrderMapper orderMapper,
                                 UserRepository userRepository, OrderRepository orderRepository,
                                 RoleValidator roleValidator, PriceCalculatorService priceCalculatorService,
                                 OrderStatusHistoryMapper orderStatusHistoryMapper,
                                 OrderStatusHistoryService orderStatusHistoryService) {
        this.dispatcherMapper = dispatcherMapper;
        this.orderMapper = orderMapper;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.roleValidator = roleValidator;
        this.priceCalculatorService = priceCalculatorService;
        this.orderStatusHistoryMapper = orderStatusHistoryMapper;
        this.orderStatusHistoryService = orderStatusHistoryService;
    }

    @Override
    public List<OrderListItemDto> getAllOrders() {
        return orderMapper.toListItemDto(orderRepository.findAll());
    }

    @Override
    public DispatcherOrderDetailsDto getOrderDetails(Long id) {
        Order order = findOrderById(id);

        return dispatcherMapper.toDispatcherOrderDetailsDto(order);
    }

    @Override
    @Transactional
    public void assignDriver(Long id, AssignDriverRequestDto dto) {
        Order order = findOrderById(id);
        User driver = findAndValidateDriver(dto.getDriverId());
        User dispatcher = getCurrentUser();

        OrderStatus oldStatus = order.getStatus();

        order.setDriver(driver);
        order.setVehicle(driver.getVehicle());
        changeStatus(order);

        orderStatusHistoryService.logStatusChange(order, oldStatus, order.getStatus(), dispatcher);

        orderRepository.save(order);
    }

    @Override
    @Transactional
    public void updateOrderStatus(Long id, UpdateOrderStatusRequestDto dto) {
        Order order = findOrderById(id);
        User dispatcher = getCurrentUser();

        OrderStatus oldStatus = order.getStatus();
        OrderStatus newStatus = dto.getStatus();

        order.setStatus(newStatus);

        orderStatusHistoryService.logStatusChange(order, oldStatus, newStatus, dispatcher);

        orderRepository.save(order);
    }

    @Override
    @Transactional
    public void updateOrderInfo(Long id, OrderRequestDto dto) {
        Order order = findOrderById(id);
        updateOrderFields(order, dto);
        orderRepository.save(order);
    }

    @Override
    @Transactional
    public void cancelOrder(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new OrderNotFoundException("Order not found with id: " + id);
        }
        Order order = findOrderById(id);
        order.setStatus(OrderStatus.CANCELLED);

        orderRepository.save(order);
    }

    @Override
    public List<AvailableDriverDto> availableDrivers() {
        List<OrderStatus> activeStatuses = List.of(OrderStatus.ASSIGNED, OrderStatus.IN_PROGRESS);
        List<User> availableDrivers = userRepository.findAvailableDrivers(activeStatuses);
        return dispatcherMapper.toAvailableDriversDto(availableDrivers);
    }

    @Override
    public List<OrderStatusHistoryDto> getOrderStatusHistory(Long id) {
        return orderStatusHistoryMapper.toDtoList(orderStatusHistoryService.getOrderHistory(id));
    }

    private Order findOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order with id " + id + "not found"));
    }

    private void changeStatus(Order order) {
        if (order.getStatus() == OrderStatus.CREATED) {
            order.setStatus(OrderStatus.ASSIGNED);
        }
    }

    private User findAndValidateDriver(Long driverId) {
        User driver = userRepository.findById(driverId)
                .orElseThrow(() -> new DriverNotFoundException("Driver with id " + driverId + " not found"));
        roleValidator.validateDriverRole(driver.getRole());
        return driver;
    }

    private void updateOrderFields(Order order, OrderRequestDto dto) {
        order.setFromAddress(dto.getFromAddress());
        order.setToAddress(dto.getToAddress());
        order.setCargoType(dto.getCargoType());
        order.setCargoDescription(dto.getCargoDescription());
        order.setWeightKg(dto.getWeightKg());
        order.setComment(dto.getComment());
        order.setPaymentMethod(dto.getPaymentMethod());
        order.setPickupTime(dto.getPickupTime());

        BigDecimal newPrice = priceCalculatorService.calculatePrice(dto.getWeightKg(), dto.getDistanceCategory());
        order.setPrice(newPrice);
    }

    private User getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findUserByEmail(email)
                .orElseThrow(() -> new UserWithEmailNotFoundException("User with email: " + email + " not found"));
    }
}
