package com.greedy.section04.practice2;

public class Heart2 implements Runnable{

	@Override
	public void run() {
		int ran = 0;
		
		try {
			for(int i = 0; i < 10; i++) {
				for(int k = 0; k < 30; k++) {
					System.out.println();
				}
				
				if(i == 0) {
					System.out.println("준비!");
					System.out.println("♡");
					System.out.println("☆");
				} else {
					ran = (int)(Math.random() * 5) + 1;
					Race.tot1 += ran;				// 값 누적 시키기
					
					/* 누적된 거리만큼 '-'로 표시하고 하트 출력하기 */
					for(int j = 0; j < Race.tot1; j++) {
						System.out.print("-");
					}
					System.out.println("♡");
					
					/* 별 출력 */
					for(int j = 0; j < Race.tot2; j++) {
						System.out.print("-");
					}
					System.out.println("☆");
				}
				Thread.sleep(1000);
			}
		} catch(InterruptedException e) {
			e.printStackTrace();
		}
	}

}
