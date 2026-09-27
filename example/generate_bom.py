#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成物料清单 (BOM) 脚本 (generate_bom.py)

规则说明:
1. 针对每一个产成品 Product (P0001 ~ P0100):
   - 随机从 0-20 里选择一个数字 k，作为下级物料数量。
   - 从半成品 Semi-product (S0001 ~ S0500) 里选择 k 个物料作为直接下级。
   - 然后以 50% 的概率决定是否继续向下传播。
2. 继续向下传播逻辑:
   - 50% 概率从 Semi-product 里选择，50% 概率从 Material 里选择。
   - 停止传播信号:
     * 只要抽中的达到了 Material (M0001 ~ M1000)，停止向下传播。
     * 如果还是 Semi-product (S0001 ~ S0500)，则可以继续往下传播 (再次以 50% 概率决定是否继续向下)。
   - 避免环形依赖: 下级物料不能选择当前链路中的祖先节点。
3. 表头规范参考 V1 核心表 bill_of_material 要求:
   - bom_no (BOM编号)
   - product_no (父项物料编码)
   - material_no (子项物料编码)
   - material_usage (单位用量/单耗)
4. 导出为 CSV 文件 (默认: bill_of_material.csv)
"""

import argparse
import csv
import os
import random
from pathlib import Path


def load_or_generate_material_ids(example_dir: Path):
    """
    加载已有的 materials.csv 或自动生成编码列表。
    """
    materials_csv = example_dir / "materials.csv"
    products = []
    semi_products = []
    raw_materials = []

    if materials_csv.exists():
        try:
            with open(materials_csv, mode="r", encoding="utf-8-sig") as f:
                reader = csv.DictReader(f)
                id_col = None
                type_col = None
                for col in reader.fieldnames or []:
                    if col in ("物料ID", "material_no", "material_id", "物料编号", "物料编码"):
                        id_col = col
                    if col in ("物料类型", "material_type", "类型", "类别"):
                        type_col = col

                if id_col:
                    for row in reader:
                        m_id = row[id_col].strip()
                        m_type = row.get(type_col, "").strip() if type_col else ""
                        if m_id.startswith("P") or "产成品" in m_type or "Product" in m_type:
                            products.append(m_id)
                        elif m_id.startswith("S") or "半成品" in m_type or "Semi" in m_type:
                            semi_products.append(m_id)
                        elif m_id.startswith("M") or "基础物料" in m_type or "Material" in m_type:
                            raw_materials.append(m_id)
        except Exception as e:
            print(f"读取 materials.csv 失败 ({e})，采用标准规则生成物料编码。")

    # 如果未能从 CSV 中读取到完整物料，则使用标准规则生成
    if len(products) != 100 or len(semi_products) != 500 or len(raw_materials) != 1000:
        products = [f"P{i:04d}" for i in range(1, 101)]
        semi_products = [f"S{i:04d}" for i in range(1, 501)]
        raw_materials = [f"M{i:04d}" for i in range(1, 1001)]

    return products, semi_products, raw_materials


def generate_bom_data(
    products,
    semi_products,
    raw_materials,
    seed: int = 42,
    max_depth: int = 20,
    round_decimals: int = 4
):
    """
    执行 BOM 生成算法。
    """
    rng = random.Random(seed)
    semi_set = set(semi_products)
    raw_set = set(raw_materials)

    # 存储生成的唯一 BOM 边关系: (bom_no, product_no, material_no) -> material_usage
    # 保证符合数据库唯一约束 uk_bom_product_material
    bom_edges = {}
    # 记录每个节点所处的最深层级用于统计分析
    node_levels = {}
    # 统计每个产成品的直接下级数
    product_direct_counts = {}

    for prod_id in products:
        node_levels[prod_id] = 0
        # 1. 针对每一个 Product，都随机从 0-20 里随机选择一个数字，作为下级物料数量
        k = rng.randint(0, 20)
        product_direct_counts[prod_id] = k

        if k == 0:
            continue

        # 2. 从 Semi-product 里选择该数字数量
        # 从 500 个 semi-product 中无放回抽样 k 个
        selected_semis = rng.sample(semi_products, k)

        for semi_id in selected_semis:
            # 添加 Product -> Semi 的 BOM 边
            bom_no = f"BOM-{prod_id}"
            # 常见工业装配用量: 1.0 ~ 4.0
            usage = round(rng.uniform(1.0, 4.0), round_decimals)
            edge_key = (bom_no, prod_id, semi_id)
            if edge_key not in bom_edges:
                bom_edges[edge_key] = usage
            node_levels[semi_id] = max(node_levels.get(semi_id, 0), 1)

            # 3. 然后 50% 的概率决定是否继续往下
            # 传播链路循环
            curr_parent = semi_id
            curr_level = 1
            # 链路路径祖先集合，防止环路 (DAG 保证)
            chain_path = [prod_id, semi_id]

            while curr_level < max_depth:
                # 50% 概率决定是否继续往下
                continue_down = rng.random() < 0.5
                if not continue_down:
                    break

                # 如果决定继续往下了:
                # 50% 概率从 semi-product 里选择，50% 概率从 material 里选择
                choose_semi = rng.random() < 0.5

                if choose_semi:
                    # 候选 semi-product 需排除当前路径上的祖先，防止成环 (A -> B -> A)
                    candidate_semis = [s for s in semi_products if s not in chain_path]
                    if not candidate_semis:
                        break
                    child = rng.choice(candidate_semis)
                    child_is_material = False
                else:
                    # 从 material 里选择
                    child = rng.choice(raw_materials)
                    child_is_material = True

                # 添加当前父件 -> 子件的 BOM 边
                # 对于半成品作为父件，BOM 编号按惯例采用 BOM-{父件编号}
                parent_bom_no = f"BOM-{curr_parent}"
                child_usage = round(rng.uniform(1.0, 5.0), round_decimals)
                child_edge_key = (parent_bom_no, curr_parent, child)
                if child_edge_key not in bom_edges:
                    bom_edges[child_edge_key] = child_usage

                node_levels[child] = max(node_levels.get(child, 0), curr_level + 1)

                # 4. 停止传播的信号:
                # 只要这抽中的这个达到了 material 就不继续向下传播，
                # 如果还是 semi 就可以继续往下传播。
                if child_is_material:
                    break
                else:
                    # 是 semi，继续往下传播
                    curr_parent = child
                    curr_level += 1
                    chain_path.append(child)

    # 格式化输出行列表
    bom_rows = []
    for (bom_no, parent_no, material_no), usage in bom_edges.items():
        bom_rows.append({
            "bom_no": bom_no,
            "product_no": parent_no,
            "material_no": material_no,
            "material_usage": f"{usage:.6f}",
        })

    # 按 bom_no, product_no, material_no 排序保证输出规范整齐
    bom_rows.sort(key=lambda r: (r["bom_no"], r["product_no"], r["material_no"]))
    return bom_rows, product_direct_counts, node_levels


def export_bom_to_csv(rows, output_path: Path, header_mode: str = "v1"):
    """
    导出 BOM 数据为 CSV 文件。
    header_mode:
        - "v1": bom_no, product_no, material_no, material_usage (V1核心表及导入预设标准名)
        - "cn": BOM编号, 父项编号, 子项编号, 物料用量 (中文业务表头，同样在预设别名库中支持)
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if header_mode == "cn":
        fieldnames = ["BOM编号", "父项编号", "子项编号", "物料用量"]
        key_map = {
            "bom_no": "BOM编号",
            "product_no": "父项编号",
            "material_no": "子项编号",
            "material_usage": "物料用量",
        }
        export_rows = [{key_map[k]: v for k, v in r.items()} for r in rows]
    else:
        fieldnames = ["bom_no", "product_no", "material_no", "material_usage"]
        export_rows = rows

    with open(output_path, mode="w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(export_rows)

    return len(export_rows)


def main():
    parser = argparse.ArgumentParser(description="生成 BOM (物料清单) CSV 文件")
    parser.add_argument(
        "-o", "--output",
        default=str(Path(__file__).resolve().parent / "bill_of_material.csv"),
        help="输出的 CSV 文件路径 (默认: example/bill_of_material.csv)"
    )
    parser.add_argument(
        "--header-mode",
        choices=["v1", "cn"],
        default="v1",
        help="表头模式: v1 (bom_no/product_no/material_no/material_usage) 或 cn (BOM编号/父项编号/子项编号/物料用量)"
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="随机数种子 (默认: 42，保证生成结果可复现)"
    )

    args = parser.parse_args()
    output_path = Path(args.output).resolve()
    example_dir = Path(__file__).resolve().parent

    print("=" * 60)
    print("开始生成 BOM (物料清单)...")

    products, semi_products, raw_materials = load_or_generate_material_ids(example_dir)
    print(f"物料库基础:")
    print(f"  - 产成品 (Product):      {len(products)} 个 (P0001 ~ P{len(products):04d})")
    print(f"  - 半成品 (Semi-product): {len(semi_products)} 个 (S0001 ~ S{len(semi_products):04d})")
    print(f"  - 基础物料 (Material):   {len(raw_materials)} 个 (M0001 ~ M{len(raw_materials):04d})")

    bom_rows, direct_counts, node_levels = generate_bom_data(
        products, semi_products, raw_materials, seed=args.seed
    )

    # 统计信息计算
    total_edges = len(bom_rows)
    all_parents = {r["product_no"] for r in bom_rows}
    all_children = {r["material_no"] for r in bom_rows}
    used_materials = {m for m in all_children if m.startswith("M")}
    used_semis = {s for s in all_children if s.startswith("S")}
    avg_k = sum(direct_counts.values()) / max(len(direct_counts), 1)
    max_k = max(direct_counts.values()) if direct_counts else 0
    min_k = min(direct_counts.values()) if direct_counts else 0

    max_level = max(node_levels.values()) if node_levels else 0

    print("\nBOM 结构生成统计:")
    print(f"  - BOM 总记录行数:        {total_edges} 条")
    print(f"  - 涉及的父件节点数:      {len(all_parents)} 个")
    print(f"  - 涉及的子件物料数:      {len(all_children)} 个 (其中半成品: {len(used_semis)} 个, 原材料: {len(used_materials)} 个)")
    print(f"  - 产成品直接下级数量:    平均 {avg_k:.1f} 个/产品 (范围: {min_k} ~ {max_k})")
    print(f"  - BOM 拓扑树最大深度:    {max_level} 层")

    written = export_bom_to_csv(bom_rows, output_path, header_mode=args.header_mode)
    print(f"\n已成功导出到: {output_path} (共 {written} 行数据)")
    print("=" * 60)


if __name__ == "__main__":
    main()
