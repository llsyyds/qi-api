package com.qimu.qiapibackend.service.impl;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyResponse;
import com.github.binarywang.wxpay.bean.notify.WxPayOrderNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayOrderQueryV3Request;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.constant.WxPayConstants;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.qimu.qiapibackend.common.ErrorCode;
import com.qimu.qiapibackend.config.EmailConfig;
import com.qimu.qiapibackend.exception.BusinessException;
import com.qimu.qiapibackend.mapper.ProductOrderMapper;
import com.qimu.qiapibackend.model.entity.ProductInfo;
import com.qimu.qiapibackend.model.entity.ProductOrder;
import com.qimu.qiapibackend.model.entity.User;
import com.qimu.qiapibackend.model.vo.PaymentInfoVo;
import com.qimu.qiapibackend.model.vo.ProductOrderVo;
import com.qimu.qiapibackend.model.vo.UserVO;
import com.qimu.qiapibackend.service.PaymentInfoService;
import com.qimu.qiapibackend.service.ProductOrderService;
import com.qimu.qiapibackend.service.UserService;
import com.qimu.qiapibackend.utils.EmailUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.qimu.qiapibackend.constant.PayConstant.ORDER_PREFIX;
import static com.qimu.qiapibackend.constant.PayConstant.QUERY_ORDER_STATUS;
import static com.qimu.qiapibackend.model.enums.PayTypeStatusEnum.WX;
import static com.qimu.qiapibackend.model.enums.PaymentStatusEnum.*;
import static com.qimu.qiapibackend.utils.WxPayUtil.getRequestHeader;


/**
 * @Author: QiMu
 * @Date: 2023/08/23 03:18:35
 * @Version: 1.0
 * @Description: 接口顺序服务impl
 */
@Slf4j
@Service
@Qualifier("WX")
@Primary
public class WxOrderServiceImpl extends ServiceImpl<ProductOrderMapper, ProductOrder> implements ProductOrderService {
    @Resource
    private RedisTemplate<String, Boolean> redisTemplate;

    @Resource
    private ProductInfoServiceImpl productInfoService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private WxPayService wxPayService;
    @Resource
    private EmailConfig emailConfig;
    @Resource
    private JavaMailSender mailSender;
    @Resource
    private UserService userService;

    @Resource
    private PaymentInfoService paymentInfoService;

    /**
     * 获取产品订单
     *
     * @param productId 产品id
     * @param loginUser 登录用户
     * @param payType   付款类型
     * @return {@link ProductOrderVo}
     */
    @Override
    public ProductOrderVo getProductOrder(Long productId, UserVO loginUser, String payType) {
        LambdaQueryWrapper<ProductOrder> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ProductOrder::getProductId, productId);
        lambdaQueryWrapper.eq(ProductOrder::getStatus, NOTPAY.getValue());
        lambdaQueryWrapper.eq(ProductOrder::getPayType, payType);
        lambdaQueryWrapper.eq(ProductOrder::getUserId, loginUser.getId());
        lambdaQueryWrapper.gt(ProductOrder::getExpirationTime, DateUtil.date(System.currentTimeMillis()));

        ProductOrder oldOrder = this.getOne(lambdaQueryWrapper);
        if (oldOrder == null) {
            return null;
        }
        ProductOrderVo productOrderVo = new ProductOrderVo();
        BeanUtils.copyProperties(oldOrder, productOrderVo);
        productOrderVo.setProductInfo(JSONUtil.toBean(oldOrder.getProductInfo(), ProductInfo.class));
        return productOrderVo;
    }

    /**
     * 保存产品订单
     *
     * @param productId 产品id
     * @param loginUser 登录用户
     * @return {@link ProductOrderVo}
     */
    @Override
    public ProductOrderVo saveProductOrder(Long productId, UserVO loginUser) {

        ProductInfo productInfo = productInfoService.getById(productId);
        if (productInfo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "商品不存在");
        }
        // 5分钟有效期
        Date date = DateUtil.date(System.currentTimeMillis());
        Date expirationTime = DateUtil.offset(date, DateField.MINUTE, 5);
        String orderNo = ORDER_PREFIX + RandomUtil.randomNumbers(20);
        ProductOrder productOrder = new ProductOrder();
        productOrder.setUserId(loginUser.getId());
        productOrder.setOrderNo(orderNo);
        productOrder.setProductId(productInfo.getId());
        productOrder.setOrderName(productInfo.getName());
        productOrder.setTotal(productInfo.getTotal());
        productOrder.setStatus(NOTPAY.getValue());
        productOrder.setPayType(WX.getValue());
        productOrder.setExpirationTime(expirationTime);
        productOrder.setProductInfo(JSONUtil.toJsonPrettyStr(productInfo));
        productOrder.setAddPoints(productInfo.getAddPoints());

        synchronized (loginUser.getUserAccount().intern()) {
            boolean saveResult = this.save(productOrder);

            // 构建支付请求
            WxPayUnifiedOrderV3Request wxPayRequest = new WxPayUnifiedOrderV3Request();
            WxPayUnifiedOrderV3Request.Amount amount = new WxPayUnifiedOrderV3Request.Amount();
            amount.setTotal(productOrder.getTotal());
            wxPayRequest.setAmount(amount);
            wxPayRequest.setDescription(productOrder.getOrderName());
            // 设置订单的过期时间为5分钟
            String format = DateUtil.format(expirationTime, "yyyy-MM-dd'T'HH:mm:ssXXX");
            wxPayRequest.setTimeExpire(format);
            wxPayRequest.setOutTradeNo(productOrder.getOrderNo());
            try {
                String codeUrl = wxPayService.createOrderV3(TradeTypeEnum.NATIVE, wxPayRequest);
                if (StringUtils.isBlank(codeUrl)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR);
                }
                productOrder.setCodeUrl(codeUrl);
                // 更新微信订单的二维码,不用重复创建
                boolean updateResult = this.updateProductOrder(productOrder);
                if (!updateResult & !saveResult) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR);
                }
            } catch (WxPayException e) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, e.getMessage());
            }
            // 构建vo
            ProductOrderVo productOrderVo = new ProductOrderVo();
            BeanUtils.copyProperties(productOrder, productOrderVo);
            productOrderVo.setProductInfo(productInfo);
            return productOrderVo;
        }
    }

    /**
     * 更新产品订单
     *
     * @param productOrder 产品订单
     * @return boolean
     */
    @Override
    public boolean updateProductOrder(ProductOrder productOrder) {
        String codeUrl = productOrder.getCodeUrl();
        Long id = productOrder.getId();
        ProductOrder updateCodeUrl = new ProductOrder();
        updateCodeUrl.setCodeUrl(codeUrl);
        updateCodeUrl.setId(id);
        return this.updateById(updateCodeUrl);
    }

    /**
     * 按订单号更新订单状态
     *
     * @param outTradeNo  外贸编号
     * @param orderStatus 订单状态
     * @return boolean
     */
    @Override
    public boolean updateOrderStatusByOrderNo(String outTradeNo, String orderStatus) {
        ProductOrder productOrder = new ProductOrder();
        productOrder.setStatus(orderStatus);
        LambdaQueryWrapper<ProductOrder> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ProductOrder::getOrderNo, outTradeNo);
        return this.update(productOrder, lambdaQueryWrapper);
    }

    /**
     * 通过out trade no获得产品订单
     *
     * @param outTradeNo 外贸编号
     * @return {@link ProductOrder}
     */
    @Override
    public ProductOrder getProductOrderByOutTradeNo(String outTradeNo) {
        LambdaQueryWrapper<ProductOrder> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ProductOrder::getOrderNo, outTradeNo);
        ProductOrder productOrder = this.getOne(lambdaQueryWrapper);
        if (productOrder == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return productOrder;
    }

    /**
     * 处理超时订单
     *
     * @param productOrder 产品订单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void processingTimedOutOrders(ProductOrder productOrder) {
        String orderNo = productOrder.getOrderNo();
        WxPayOrderQueryV3Request wxPayOrderQueryV3Request = new WxPayOrderQueryV3Request();
        wxPayOrderQueryV3Request.setOutTradeNo(orderNo);
        WxPayOrderQueryV3Result wxPayOrderQueryV3Result = null;
        try {
            wxPayOrderQueryV3Result = wxPayService.queryOrderV3(wxPayOrderQueryV3Request);
            String tradeState = wxPayOrderQueryV3Result.getTradeState();
            // 订单已支付
            if (tradeState.equals(SUCCESS.getValue())) {
                this.updateOrderStatusByOrderNo(orderNo, SUCCESS.getValue());
                // 用户余额补发
                userService.addWalletBalance(productOrder.getUserId(), productOrder.getAddPoints());
                // 创建支付记录
                PaymentInfoVo paymentInfoVo = new PaymentInfoVo();
                BeanUtils.copyProperties(wxPayOrderQueryV3Result, paymentInfoVo);
                paymentInfoService.createPaymentInfo(paymentInfoVo);
                // 发送邮件
                User user = userService.getById(productOrder.getUserId());
                if (StringUtils.isNotBlank(user.getEmail())) {
                    try {
                        ProductOrder productOrderByOutTradeNo = this.getProductOrderByOutTradeNo(productOrder.getOrderNo());
                        new EmailUtil().sendPaySuccessEmail(user.getEmail(), mailSender, emailConfig, productOrderByOutTradeNo.getOrderName(),
                                String.valueOf(new BigDecimal(productOrderByOutTradeNo.getTotal()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)));
                        log.info("发送邮件：{}，成功", user.getEmail());
                    } catch (Exception e) {
                        log.error("发送邮件：{}，失败：{}", user.getEmail(), e.getMessage());
                    }
                }
                log.info("超时订单{},状态已更新", orderNo);
            }
            if (tradeState.equals(NOTPAY.getValue())) {
                wxPayService.closeOrderV3(orderNo);
                this.updateOrderStatusByOrderNo(orderNo, CLOSED.getValue());
                log.info("超时订单{},订单已关闭", orderNo);
            }
            redisTemplate.delete(QUERY_ORDER_STATUS + orderNo);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 付款通知
     *
     * @param notifyData 通知数据
     * @param request    要求
     * @return {@link String}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String doPaymentNotify(String notifyData, HttpServletRequest request) {
        RLock rLock = redissonClient.getLock("notify:WxOrder:lock");
        try {
            log.info("【微信支付回调通知处理】:{}", notifyData);
            WxPayOrderNotifyV3Result result = wxPayService.parseOrderNotifyV3Result(notifyData, getRequestHeader(request));
            // 解密后的数据
            WxPayOrderNotifyV3Result.DecryptNotifyResult notifyResult = result.getResult();
            if (WxPayConstants.WxpayTradeStatus.SUCCESS.equals(notifyResult.getTradeState())) {
                String outTradeNo = notifyResult.getOutTradeNo();
                if (rLock.tryLock(0, -1, TimeUnit.MILLISECONDS)) {
                    ProductOrder productOrder = this.getProductOrderByOutTradeNo(outTradeNo);
                    // 处理重复通知
                    if (SUCCESS.getValue().equals(productOrder.getStatus())) {
                        redisTemplate.delete(QUERY_ORDER_STATUS + outTradeNo);
                        return WxPayNotifyResponse.success("支付成功");
                    }
                    // 更新订单状态
                    boolean updateOrderStatus = this.updateOrderStatusByOrderNo(outTradeNo, SUCCESS.getValue());
                    redisTemplate.delete(QUERY_ORDER_STATUS + outTradeNo);
                    // 更新用户积分
                    boolean addWalletBalance = userService.addWalletBalance(productOrder.getUserId(), productOrder.getAddPoints());
                    // 发送邮件
                    User user = userService.getById(productOrder.getUserId());
                    if (StringUtils.isNotBlank(user.getEmail())) {
                        try {
                            new EmailUtil().sendPaySuccessEmail(user.getEmail(), mailSender, emailConfig, productOrder.getOrderName(),
                                    String.valueOf(new BigDecimal(productOrder.getTotal()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)));
                            log.info("发送邮件：{}，成功", user.getEmail());
                        } catch (Exception e) {
                            log.error("发送邮件：{}，失败：{}", user.getEmail(), e.getMessage());
                        }
                    }
                    // 保存支付记录
                    PaymentInfoVo paymentInfoVo = new PaymentInfoVo();
                    BeanUtils.copyProperties(notifyResult, paymentInfoVo);
                    boolean paymentResult = paymentInfoService.createPaymentInfo(paymentInfoVo);
                    if (paymentResult && updateOrderStatus && addWalletBalance) {
                        log.info("【支付回调通知处理成功】");
                        return WxPayNotifyResponse.success("支付成功");
                    }
                }
            }
            if (WxPayConstants.WxpayTradeStatus.PAY_ERROR.equals(notifyResult.getTradeState())) {
                log.error("【微信支付失败】" + result);
                throw new WxPayException("支付失败");
            }
            if (WxPayConstants.WxpayTradeStatus.USER_PAYING.equals(notifyResult.getTradeState())) {
                throw new WxPayException("支付中");
            }
            return WxPayNotifyResponse.fail("支付失败");
        } catch (Exception e) {
            log.error("【支付失败】" + e.getMessage());
            return WxPayNotifyResponse.fail("支付失败");
        } finally {
            if (rLock.isHeldByCurrentThread()) {
                System.out.println("unLock: " + Thread.currentThread().getId());
                rLock.unlock();
            }
        }
    }
}




