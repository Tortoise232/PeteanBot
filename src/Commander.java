import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import bwapi.Color;
import bwapi.Game;
import bwapi.Player;
import bwapi.Position;
import bwapi.TilePosition;
import bwapi.Unit;
import bwapi.UnitType;
import bwapi.UpgradeType;

public class Commander {
	private static Game game;
	private static Player me;
	public HashSet<Unit> squad = new HashSet<Unit>();
	public HashSet<Position> enemyBuildingMemory = new HashSet<Position>();
	public TilePosition myTarget;
	public Commander(Game theGame, Player me) {
		this.game = theGame;
		this.me = me;
	}
	
	public void getInBunker(Unit bunker, Unit unit) {
		if(bunker.getType() != UnitType.Terran_Bunker || !unit.canMove() || !bunker.canLoad(unit))
			return;
		unit.rightClick(bunker);
	}
	
	public int evaluateThreat(Unit unit) {
		int threatLevel = 0;
		int nrOfBaddies = 0;
		int nrOfGoodies = 0;
		List<Unit> neighbours = unit.getUnitsInWeaponRange(unit.getType().groundWeapon());
		for(Unit neighbour: neighbours) {
			//neighbour is enemy
			if(neighbour.canAttack(unit) && neighbour.getPlayer().isEnemy(unit.getPlayer())){
				if(neighbour.getType().groundWeapon().damageAmount() > unit.getHitPoints())
					threatLevel += 3;
				else
					threatLevel += 1;
				nrOfBaddies ++;
			}
			if(neighbour.getPlayer() == unit.getPlayer())
				nrOfGoodies ++;
		}
		int squadSizeDiff = nrOfBaddies - nrOfGoodies;
		if(squadSizeDiff <= 0) {
			if(squadSizeDiff < -(nrOfGoodies))
				threatLevel -= 2;
			else
				threatLevel -= 1;
		}
		else
			threatLevel += 1;
		return threatLevel;
	}
	
	
	public Unit seeEnemy(Unit myUnit) {
    	int bestDistance = 999999;
    	Unit bestEnemy = null;
    	for(Unit enemy: myUnit.getUnitsInRadius(100)) {
    		if(enemy.getPlayer() == game.enemy())
    			if(bestDistance > myUnit.getDistance(enemy)) {
    				bestDistance = myUnit.getDistance(enemy);
    				bestEnemy = enemy;
    			}	    			
    	}
    	return bestEnemy;
    }
	
	public void sendMarines(HashSet<Unit> squad, TilePosition target) {
    	if(enemyBuildingMemory.size() > 0)
    	{
    		for(Unit myUnit: squad) {
    			if(!myUnit.isIdle())
    				continue;
    			Unit closeEnemy = seeEnemy(myUnit);
    			if(closeEnemy == null) {
    				myUnit.attack(target.toPosition());
    				game.drawLineMap(myUnit.getPosition(), target.toPosition(), Color.Red);
    			}
    			else {
    				myUnit.attack(closeEnemy);
    				game.drawLineMap(myUnit.getPosition(), closeEnemy.getPosition(), Color.Red);

    			}
    		}
    	}
    } 
	
	public void defendBase() {
		for(Unit myUnit:squad) {
			myUnit.move(me.getStartLocation().toPosition());
		}
	}
	
	public TilePosition establishTarget() {
		for (Unit u : game.enemy().getUnits()) {
        	//if this unit is in fact a building
        	if (u.getType().isBuilding()) {
        		//check if we have it's position in memory and add it if we don't
        		if (!enemyBuildingMemory.contains(u.getPosition())) 
        			enemyBuildingMemory.add(u.getPosition());
        	}
        }
		return enemyBuildingMemory.iterator().next().toTilePosition();
	}
	
	public void evaluateGame() {
		System.out.println("COMMANDER STARTS EVALUATING");
		myTarget = establishTarget();
		if(squad.size() >= 30)
			sendMarines(squad, myTarget);
		System.out.println("COMMANDER ENDS EVALUATING");
	}
}