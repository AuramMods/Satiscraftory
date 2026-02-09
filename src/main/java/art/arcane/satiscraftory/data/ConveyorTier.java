package art.arcane.satiscraftory.data;

public enum ConveyorTier {
    MARK_1(60),
    MARK_2(30),
    MARK_3(15),
    MARK_4(7),
    MARK_5(3),
    MARK_6(1);
    
    private final int speed;
    
    ConveyorTier(int speed) {
        this.speed = speed;
    }
}
