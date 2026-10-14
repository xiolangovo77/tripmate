package com.ecnu.tripmate.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.ItineraryItem;
import com.ecnu.tripmate.mapper.ItineraryItemMapper;
import com.ecnu.tripmate.service.ItineraryItemService;
import org.springframework.stereotype.Service;

/**
 * 行程明细表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class ItineraryItemServiceImpl extends ServiceImpl<ItineraryItemMapper, ItineraryItem>  implements ItineraryItemService{

}
