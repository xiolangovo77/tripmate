package com.ecnu.tripmate.controller;

import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecnu.tripmate.model.entity.ItineraryItem;
import com.ecnu.tripmate.service.ItineraryItemService;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.List;

/**
 * 行程明细表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/itineraryItem")
public class ItineraryItemController {

}
