package com.guanxian.platform.collaboration;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class CollaborationService {
    private final List<CollaborationView> items = List.of(
            new CollaborationView("C001", "高压燃气管道零泄漏阀门联合评估",
                    List.of("北京市政建设集团", "北方阀门制造有限公司"), "徐明", "联合评估", "高",
                    "确认试验场地与技术参数", LocalDate.of(2026, 8, 18), 62),
            new CollaborationView("C002", "老旧街区地下管线综合探测需求对接",
                    List.of("首都城市更新", "中勘研究院"), "陈晓", "方案沟通", "中",
                    "上传初步勘察方案", LocalDate.of(2026, 8, 21), 38),
            new CollaborationView("C003", "监测平台与数字孪生底座联合方案",
                    List.of("北方燃气安全", "京城管网"), "王志远", "待受理", "中",
                    "确认双方技术联系人", LocalDate.of(2026, 8, 23), 16),
            new CollaborationView("C004", "非开挖修复评价标准案例征集",
                    List.of("北京地下管线协会", "北京建工市政"), "张全超", "已完成", "低",
                    "归档评审意见", LocalDate.of(2026, 8, 12), 100));

    public List<CollaborationView> findAll() {
        return items;
    }
}
