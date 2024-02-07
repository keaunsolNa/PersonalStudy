package com.greedy.section04.practice2;

public class Race {

	/* 처음부터 얼만큼 움직였는지를 누적시킬 public static형 int  변수 */
	public static int tot1 = 0;				// 하트의 누적거리
	public static int tot2 = 0;				// 스타의 누적거리
	
	public static void main(String[] args) {

		Thread th1 = new Thread(new Heart2());
		Thread th2 = new Thread(new Star2());
	
		try {
			th1.start();
			Thread.sleep(500);
			th2.start();
			
			th1.join();
			th2.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} 
		
		String str = Race.tot1 > Race.tot2? "♡ 승리!" : 
					(Race.tot1 < Race.tot2? "☆ 승리!" : "무승부!");
		
		System.out.println(str);
	} 
}
