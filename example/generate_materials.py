#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成物料主数据脚本 (generate_materials.py)

功能说明:
1. 生成 1000 个基础物料 (Raw Material)，编码规则: M0001 ~ M1000
2. 生成 500 个半成品物料 (Semi-product)，编码规则: S0001 ~ S0500
3. 生成 100 个产成品物料 (Finished Product)，编码规则: P0001 ~ P0100
4. 表格表头参考 V1 物料需求: 物料ID、名称、规格型号、单位、物料类型等
5. 导出为 CSV 文件 (默认: materials.csv)
"""

import argparse
import csv
import os
import random
from pathlib import Path

# 预置工业制造中常用的词库，使生成的名称与规格更加真实拟真
RAW_MATERIAL_CATEGORIES = [
    # 金属与结构件原材料
    ("304不锈钢板", ["1.5mm*1219*2438", "2.0mm*1000*2000", "3.0mm*1500*3000"], "张"),
    ("6061-T6铝合金棒", ["Φ20*2500mm", "Φ35*3000mm", "Φ50*3000mm"], "根"),
    ("冷轧钢带", ["0.8mm*150mm*C", "1.2mm*200mm*C"], "卷"),
    ("黄铜棒", ["H59-1 Φ12*2000mm", "H62 Φ25*2500mm"], "根"),
    ("镀锌无缝钢管", ["DN25*3.2*6000mm", "DN50*3.5*6000mm"], "根"),
    ("钛合金板材", ["TC4 1.0mm*500*1000", "TC4 2.5mm*500*1000"], "张"),
    # 电子电气元器件
    ("贴片陶瓷电容", ["0603 10uF 25V X7R", "0805 100nF 50V", "1206 22uF 16V"], "千只"),
    ("贴片精密电阻", ["0603 10kΩ ±1%", "0805 100Ω ±0.5%", "1206 1kΩ ±1%"], "千只"),
    ("功率MOS管", ["TO-220 60V 50A", "TO-252 100V 30A", "PDFN5*6 40V 100A"], "只"),
    ("微控制器MCU芯片", ["LQFP-64 32位 72MHz", "QFN-48 120MHz", "LQFP-100 168MHz"], "只"),
    ("高频变压器磁芯", ["EE19 PC40", "PQ2625 Ferrite", "ETD39 MnZn"], "套"),
    ("光耦隔离器", ["SOP-4 5000Vrms", "DIP-8 2500Vrms"], "只"),
    # 标准紧固件
    ("内六角圆柱头螺钉", ["M3*12 12.9级发黑", "M4*16 不锈钢304", "M5*20 8.8级镀锌"], "包"),
    ("十字槽盘头自攻螺丝", ["ST2.9*9.5 镀镍", "ST3.5*12 碳钢"], "包"),
    ("弹簧垫圈", ["M4 65Mn发蓝", "M5 304不锈钢", "M6 碳钢镀锌"], "包"),
    ("自锁防松螺母", ["M4 尼龙圈锁紧", "M5 304不锈钢", "M6 8级发黑"], "包"),
    # 化学原料与辅料
    ("环保无铅焊锡丝", ["Sn99.3Cu0.7 Φ0.8mm 500g", "Sn96.5Ag3.0Cu0.5 Φ0.6mm"], "卷"),
    ("工业导热硅脂", ["TG-300 3.0W/m·K 1kg", "TG-500 5.0W/m·K 500g"], "罐"),
    ("三防绝缘漆", ["环保快干型 20L", "防潮阻燃型 5L"], "桶"),
    ("阻燃ABS塑胶颗粒", ["UL94-V0 黑色 25kg", "UL94-V0 本色 25kg"], "袋"),
    ("聚四氟乙烯密封带", ["12mm*0.1mm*10m", "19mm*0.2mm*15m"], "卷"),
    ("环氧树脂灌封胶", ["EP-101 双组份 20kg/组", "EP-202 阻燃导热 10kg/组"], "组"),
]

SEMI_PRODUCT_CATEGORIES = [
    # 电子总成 / 模块
    ("主控核心板组件", ["REV-B4 STM32架构", "REV-C1 双核DSP", "REV-A3 工业网关核心"], "块"),
    ("数字功率驱动板", ["60V/30A 三相逆变", "100V/50A 双极性", "48V/20A FOC矢量"], "块"),
    ("隔离式DC-DC电源模块", ["24V转5V/12V 50W", "48V转24V 150W", "12V转3.3V 15W"], "块"),
    ("高精度传感器调理板", ["4-20mA转数字 4通道", "热电偶信号采集模块", "霍尔差分采样板"], "块"),
    ("通信接口拓展板", ["CAN-FD/RS485双总线", "双口千兆以太网PHY板", "Modbus-RTU扩展卡"], "块"),
    # 机械传动 / 结构组件
    ("无刷电机转子总成", ["Φ45*60mm 钕铁硼磁极", "Φ60*80mm 动平衡等级G2.5"], "套"),
    ("定子绕组与铁芯总成", ["12槽14极 0.2mm硅钢片", "18槽 0.35mm高导磁"], "套"),
    ("行星减速齿轮箱总成", ["速比1:10 额定50N·m", "速比1:25 额定120N·m"], "台"),
    ("精密滚珠丝杠总成", ["C5级 导程5mm 行程300mm", "C3级 导程10mm 行程500mm"], "根"),
    ("压铸铝散热壳体总成", ["阳极氧化发黑 200*150*60mm", "压铸喷砂 250*180*80mm"], "件"),
    ("电磁制动器组件", ["DC24V 15N·m 带释放手柄", "DC24V 30N·m 弹簧常闭式"], "套"),
    ("液压电磁换向阀总成", ["3位4通 额定压力21MPa", "2位4通 额定流量40L/min"], "件"),
]

FINISHED_PRODUCT_CATEGORIES = [
    ("智能伺服驱动一体机", ["SERVO-500W-220V", "SERVO-750W-220V", "SERVO-1500W-380V"], "台"),
    ("工业级六轴机械臂控制器", ["RC-600A-V2 载荷10kg", "RC-800B-V3 载荷20kg"], "台"),
    ("高精度激光雷达测距仪", ["LIDAR-300M 16线", "LIDAR-500M 32线工业级"], "台"),
    ("自动化AGV主驱动单元", ["AGV-DRV-24V-800W", "AGV-DRV-48V-1500W"], "套"),
    ("工业边缘计算网关", ["EG-2000 8网口+CAN", "EG-5000 AI算力20TOPS"], "台"),
    ("变频恒压供水控制机柜", ["PUMP-CTRL-15KW 3路联控", "PUMP-CTRL-30KW 4路联控"], "台"),
    ("分布式PLC主控站", ["PLC-PAC-300 支持Profinet", "PLC-PAC-500 支持EtherCAT"], "台"),
    ("大功率储能双向变流器", ["PCS-50KW-400V 离并网双模", "PCS-100KW-400V 模块化"], "台"),
]


def generate_materials_data(seed: int = 42):
    """
    生成三类物料主数据:
    1. 基础物料 (Raw Material): 1000个, M0001 ~ M1000
    2. 半成品 (Semi-product): 500个, S0001 ~ S0500
    3. 产成品 (Product): 100个, P0001 ~ P0100
    """
    rng = random.Random(seed)
    rows = []

    # 1. 基础物料 1000 个 (M0001 ~ M1000)
    for i in range(1, 1001):
        mat_id = f"M{i:04d}"
        cat_name, specs, default_unit = rng.choice(RAW_MATERIAL_CATEGORIES)
        spec = rng.choice(specs)
        # 为防止同类别名称单调，组合型号编号后缀
        name = f"{cat_name}-{i:04d}"
        unit = default_unit
        mat_type = "基础物料"
        rows.append({
            "物料ID": mat_id,
            "名称": name,
            "规格型号": spec,
            "单位": unit,
            "物料类型": mat_type,
        })

    # 2. 半成品 500 个 (S0001 ~ S0500)
    for i in range(1, 501):
        semi_id = f"S{i:04d}"
        cat_name, specs, default_unit = rng.choice(SEMI_PRODUCT_CATEGORIES)
        spec = rng.choice(specs)
        name = f"{cat_name}-{i:04d}"
        unit = default_unit
        mat_type = "半成品"
        rows.append({
            "物料ID": semi_id,
            "名称": name,
            "规格型号": spec,
            "单位": unit,
            "物料类型": mat_type,
        })

    # 3. 产成品 100 个 (P0001 ~ P0100)
    for i in range(1, 101):
        prod_id = f"P{i:04d}"
        cat_name, specs, default_unit = rng.choice(FINISHED_PRODUCT_CATEGORIES)
        spec = rng.choice(specs)
        name = f"{cat_name}-{i:04d}"
        unit = default_unit
        mat_type = "产成品"
        rows.append({
            "物料ID": prod_id,
            "名称": name,
            "规格型号": spec,
            "单位": unit,
            "物料类型": mat_type,
        })

    return rows


def export_to_csv(rows, output_path: Path, header_mode: str = "cn"):
    """
    导出物料主数据为 CSV 文件。
    header_mode:
        - "cn": 物料ID, 名称, 规格型号, 单位, 物料类型 (满足中文 ERP/V1 业务表头要求)
        - "v1": material_no, material_name, specification, unit, material_type (满足数据库字段映射要求)
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if header_mode == "v1":
        fieldnames = ["material_no", "material_name", "specification", "unit", "material_type"]
        key_map = {
            "物料ID": "material_no",
            "名称": "material_name",
            "规格型号": "specification",
            "单位": "unit",
            "物料类型": "material_type",
        }
        export_rows = [{key_map[k]: v for k, v in r.items()} for r in rows]
    else:
        fieldnames = ["物料ID", "名称", "规格型号", "单位", "物料类型"]
        export_rows = rows

    # 使用 utf-8-sig 以便在各类操作系统和 Excel 中无乱码打开
    with open(output_path, mode="w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(export_rows)

    return len(export_rows)


def main():
    parser = argparse.ArgumentParser(description="生成物料基础数据 CSV 文件 (基础物料、半成品、产成品)")
    parser.add_argument(
        "-o", "--output",
        default=str(Path(__file__).resolve().parent / "materials.csv"),
        help="输出的 CSV 文件路径 (默认: example/materials.csv)"
    )
    parser.add_argument(
        "--header-mode",
        choices=["cn", "v1"],
        default="cn",
        help="表头模式: cn (物料ID/名称/规格型号/单位/物料类型) 或 v1 (material_no/material_name/specification/unit/material_type)"
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="随机数种子 (默认: 42，保证生成结果可复现)"
    )

    args = parser.parse_args()
    output_path = Path(args.output).resolve()

    print("=" * 60)
    print("开始生成物料主数据...")
    rows = generate_materials_data(seed=args.seed)

    count_m = sum(1 for r in rows if r["物料ID"].startswith("M"))
    count_s = sum(1 for r in rows if r["物料ID"].startswith("S"))
    count_p = sum(1 for r in rows if r["物料ID"].startswith("P"))

    print(f"  - 基础物料 (Material):     {count_m} 个 (编码: M0001 ~ M1000)")
    print(f"  - 半成品物料 (Semi-product): {count_s} 个 (编码: S0001 ~ S0500)")
    print(f"  - 产成品物料 (Product):      {count_p} 个 (编码: P0001 ~ P0100)")
    print(f"  - 物料总量合计:              {len(rows)} 个")

    written = export_to_csv(rows, output_path, header_mode=args.header_mode)
    print(f"已成功导出到: {output_path} (共 {written} 行数据)")
    print("=" * 60)


if __name__ == "__main__":
    main()
