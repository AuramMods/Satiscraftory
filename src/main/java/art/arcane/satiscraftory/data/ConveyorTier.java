package art.arcane.satiscraftory.data;

public enum ConveyorTier {
    MARK_1(60),
    MARK_2(30),
    MARK_3(15),
    MARK_4(8),
    MARK_5(4),
    MARK_6(2);
    
    private final int speed;
    
    ConveyorTier(int speed) {
        this.speed = speed;
    }

    public int getTravelTicksPerBlock() {
        return speed;
    }
}
