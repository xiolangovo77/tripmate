package com.ecnu.tripmate.service.impl.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.Itinerary;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.service.ItineraryService;
import org.springframework.stereotype.Service;

/**
 * 行程表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class ItineraryServiceImpl extends ServiceImpl<ItineraryMapper, Itinerary>  implements ItineraryService{

}
