package com.greedy.section04.practice2;

public class Star2 implements Runnable{

	@Override
	public void run() {
		
		int ran = 0;
		try {
			for(int i =0; i < 10; i++) {
				for(int k =0; k < 30; k++) {
					System.out.println();
				}
				
				if(i ==0) {
					System.out.println("시작!!!");
					System.out.println("♡");
					System.out.println("☆");
				} else {
					
					/* 하트 출력 */
					for(int j = 0; j < Race.tot1; j++) {
						System.out.print("-");
					}
					System.out.println("♡");
					
					ran = (int)(Math.random() * 5) +1;
					Race.tot2 += ran;
				
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
