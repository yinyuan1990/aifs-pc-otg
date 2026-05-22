package com.acard.acard.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * AI 扑克牌显示组件
 * 
 * 显示内容：
 * - 中间：牌的点数（32px）
 * - 左上角：花色图标 + 花色名字（无背景）
 * 
 * 花色：
 * - 红桃 (HEART) - 红色 #FF383C
 * - 方块 (DIAMOND) - 红色 #FF383C
 * - 黑桃 (SPADE) - 白色 #FAFAFA
 * - 梅花 (CLUB) - 白色 #FAFAFA
 */
public class AiCardItem extends StackPane {

    /**
     * 花色枚举
     */
    public enum Suit {
        HEART("红桃", "ht.png", "#FF383C"),      // 红桃
        DIAMOND("方块", "fb.png", "#FF383C"),    // 方块
        SPADE("黑桃", "heit.png", "#FAFAFA"),    // 黑桃（白色文字）
        CLUB("梅花", "mh.png", "#FAFAFA");       // 梅花（白色文字）

        private final String name;
        private final String iconFile;
        private final String color;

        Suit(String name, String iconFile, String color) {
            this.name = name;
            this.iconFile = iconFile;
            this.color = color;
        }

        public String getName() { return name; }
        public String getIconFile() { return iconFile; }
        public String getColor() { return color; }
    }

    private final Label centerLabel;      // 中间大字号点数
    private final Label suitNameLabel;    // 左上角花色名字
    private final ImageView suitIcon;     // 花色图标
    private final HBox cornerBox;         // 左上角容器

    private String rank = "";             // 点数 (2-10, J, Q, K, A)
    private Suit suit = Suit.HEART;       // 花色

    public AiCardItem() {
        // 设置背景样式（和占位符一致）
        this.setStyle("-fx-background-color: #292929; -fx-background-radius: 2;");
        this.setMinSize(0, 0);

        // ========== 中间大字号点数 ==========
        centerLabel = new Label("");
        centerLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 32px; -fx-font-weight: bold; -fx-font-family: 'PingFang HK';");

        // ========== 左上角：花色图标 + 花色名字（无背景） ==========
        cornerBox = new HBox(4);
        cornerBox.setAlignment(Pos.CENTER_LEFT);
        // 限制高度，防止撑满
        cornerBox.setMaxHeight(16);
        cornerBox.setMinHeight(16);
        cornerBox.setPrefHeight(16);

        // 花色图标
        suitIcon = new ImageView();
        suitIcon.setFitWidth(12);
        suitIcon.setFitHeight(12);
        suitIcon.setPreserveRatio(true);

        // 左上角花色名字标签
        suitNameLabel = new Label("");
        suitNameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-font-family: 'PingFang HK';");

        cornerBox.getChildren().addAll(suitIcon, suitNameLabel);
        cornerBox.setVisible(false);  // 默认隐藏，有数据时显示
        // 防止宽度撑满
        cornerBox.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        // 设置对齐 - 左上角
        StackPane.setAlignment(centerLabel, Pos.CENTER);
        StackPane.setAlignment(cornerBox, Pos.TOP_LEFT);
        StackPane.setMargin(cornerBox, new Insets(6, 0, 0, 6));

        // 添加子组件
        this.getChildren().addAll(centerLabel, cornerBox);
    }

    /**
     * 设置卡牌数据
     * @param rank 点数 (2-10, J, Q, K, A)
     * @param suit 花色
     */
    public void setCard(String rank, Suit suit) {
        this.rank = rank;
        this.suit = suit;

        // 更新中间大字号点数
        centerLabel.setText(rank);

        // 更新左上角花色名字和颜色
        suitNameLabel.setText(suit.getName());
        suitNameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-font-family: 'PingFang HK'; -fx-text-fill: " + suit.getColor() + ";");

        // 更新花色图标
        try {
            String iconPath = "/com/acard/design/" + suit.getIconFile();
            Image icon = new Image(getClass().getResourceAsStream(iconPath));
            suitIcon.setImage(icon);
        } catch (Exception e) {
            System.err.println("加载花色图标失败: " + suit.getIconFile() + " - " + e.getMessage());
        }

        // 显示左上角容器
        cornerBox.setVisible(true);
    }

    /**
     * 清空卡牌显示
     */
    public void clear() {
        this.rank = "";
        this.suit = Suit.HEART;
        centerLabel.setText("");
        suitNameLabel.setText("");
        suitIcon.setImage(null);
        cornerBox.setVisible(false);
    }

    public String getRank() { return rank; }
    public Suit getSuit() { return suit; }
}
